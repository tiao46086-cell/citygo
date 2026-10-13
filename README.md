# CityGo

本地生活服务。在黑马点评的商户、登录和优惠券模型上，做了缓存更新和优惠券秒杀。

课程来源：[黑马程序员 Redis 入门到实战](https://www.bilibili.com/video/BV1cr4y1671t)。缓存旁路和 Lua 秒杀的思路来自课程，下面列的是这个仓库里实际写了的行为。

目标是完成 v1.0，不再规划 v1.1，也不主动增加技术栈。协作规则见 [AGENTS.md](AGENTS.md)，实现来源、已知限制和验证范围见 [CITYGO_CONTEXT.md](CITYGO_CONTEXT.md)。

## 当前已有实现

- 登录：验证码放在 Redis，登录后换成 token。登录页的「体验 CityGo」由后端分配体验用户，并返回同样的 token。体验用户手机号以 190 开头，不能走验证码登录。同一 IP 10 分钟最多 10 次。同时最多 100 个体验会话。体验 token 在锁内写好，之后每次访问只续 2 小时，不续成普通登录的 25 天。手机号已存在时不会把旧账号交出去。名额满了之后，只回收已经没有登录态的账号：未支付订单先取消并退库存，订单行留下，并用新的订单号作为分界，这个号以及更早的订单不再展示。评论、收藏会清掉，并从点赞里移除；只有真的删掉点赞成员才减少笔记的点赞数。不接真实短信。未登录的接口返回 401。
- 商户详情：先查本机 Caffeine，未命中再走 Cache Aside。空值只放 Redis。Redisson 互斥重建，过期时间加随机偏移。更新时数据库提交后再删这两级缓存，1 秒后再删一次。单机内存，不向其他实例广播失效。
- 一级评论：发布、分页、删除自己的。只有这一行真的删掉时才把评论数减一。笔记收藏：收藏、取消、我的收藏列表。同一用户对同一篇笔记只有一行。点赞同一个人只记一次，再点取消。新建笔记的点赞数和评论数由服务端写成 0。
- 秒杀：先生成订单号，再用 Lua 判断活动时间、库存和一人一单，并记下这次预占的订单号。通过后把消息发到 RabbitMQ，等发布确认。
- 下单消费者：手动应答。事务提交后再 ack。同一个订单号再投递不重复扣库存。数据库库存扣失败时保留 status=7 失败行，提交后按订单号回补 Redis；回补异常保留 pending，由重投或扫描继续恢复。
- 正常重试路径首次加两次重试共 3 次，中间隔 5 秒；转发确认或补偿失败时可能重新入队，不能保证所有故障路径严格只有三次。创建和终止通过同一订单行的 `status=0` 条件更新裁决，终止为 7 后退回 Redis 预占，相同消息再投递不再建单或扣库存。若订单已创建，保留 `hold`，只删 `pending`，不增加库存。终止后尝试转入 `seckill.order.fail`；该队列未确认收到时原下单消息仍会应答，不能保证每条失败消息都保留在失败队列。
- 普通券：一个事务里扣 `tb_voucher.stock` 并创建未支付订单。管理员新增普通券时，库存写入这一列。新增秒杀券时 type 固定为 1。和秒杀共用我的订单、模拟支付，以及 15 分钟未支付取消。不走 Redis 预占，也不新增队列。超时取消消息沿用原来的延迟队列；这条消息发不出去时订单仍保留，由每 5 秒一次的扫描按创建时间取消并回补库存。
- 未支付订单 15 分钟后取消。秒杀券先改数据库并退回秒杀库存，提交之后再按订单号回补 Redis。普通券只把 `tb_voucher.stock` 加回去。取消后同一个人可以再抢秒杀券。
- 支付只把未支付且未超时的订单改成已支付，不接入支付宝或微信，也不再扣库存。普通券和秒杀券用同一个支付接口。
- `GET /voucher-order/me` 分页返回当前用户已经入库、且晚于体验分界的订单。`GET /voucher-order/{id}` 查询处理中、下单失败、未支付、已支付、已取消、已超时。
- 发布确认超时留下的 `pending` 每 5 秒检查一次，只有 `state=waiting` 且到达 `nextRetryAt` 才按原订单号补发（默认间隔 10 秒），查询不会推迟这个时间。失败终态优先回补，即使 state=retrying；其他已入库订单删除 `pending`、保留 `hold`。扫描还会重试取消后的 Redis 回补和处理超时未支付订单。
- 活动时间按北京时间解释；订单号用真实 UTC 候选值，并经 Redis 原子发号兼容旧订单号和体验分界。上线切换时停止旧版实例，避免混用算法。点赞使用已有 Redisson 锁，SQL 异常时补偿 Redis 增删；这不等于跨库强一致。

没有做：评分、关注 Feed、GEO、签到、Elasticsearch、压测数字、真实支付。上面这些做完后，功能范围先停在这里。

源码包名为 `com.citygo`，页面目录为 `src/main/resources/nginx-1.18.0/html/citygo`，基础脚本为 `citygo.sql`。当前默认数据库名为 `citygo`；本地工作目录名不影响构建。`deploy/import-demo.ps1` 新生成的备份文件以 `citygo-` 开头。

当前 Compose 项目名固定为 `citygo`。旧部署升级前需要备份并将原数据库复制到 `citygo`，核对表、行数和业务数据，同时迁移或显式复用原数据卷。直接用新项目名启动会创建新卷，不能当作旧部署的数据迁移；不要执行 `down -v`。

## 本地运行

需要 Java 8、Maven、本机 MySQL（库名 `citygo`）、Redis，以及 RabbitMQ。新建库先导入 `citygo.sql`，再执行 `sql/citygo-upgrade.sql` 增加普通券库存和体验订单分界列；需要演示数据再执行 `sql/citygo-demo.sql`。已有库不要重新导入含 DROP TABLE 的基础 SQL，可单独执行升级脚本。只把 RabbitMQ 放进 Docker 时：

```text
docker run -d --name citygo-rabbitmq -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=citygo -e RABBITMQ_DEFAULT_PASS=citygo rabbitmq:3-management
```

整套用 Compose 启动时，MySQL、Redis、RabbitMQ 和应用在同一个网络里，页面由 Nginx 提供，地址是 http://127.0.0.1:8080 。这套不映射 3306、6379、5672，本机原来的数据库和队列可以继续留着。8080 如果已经被本机 Nginx 占用，要先停掉那个进程。Redis 打开 AOF，RabbitMQ 固定节点名 `rabbit@citygo-rabbitmq`，三者都用命名卷。

新的空 MySQL 卷会先导入 `citygo.sql`，再执行 `sql/citygo-upgrade.sql` 补上普通券库存列和体验账号的订单分界，然后导入 `sql/citygo-demo.sql`。已经存在的 `citygo-mysql` 卷不会重跑初始化。要给已有库换上广州演示数据，运行 `deploy/import-demo.ps1`：先备份到 `backup/`，再执行升级脚本和演示脚本，删掉 Redis 里的商户缓存，并重启应用以清掉本机 Caffeine。不要删除 `citygo-mysql` 卷。结构升级不依赖演示数据，也可以单独执行 `sql/citygo-upgrade.sql`。

```text
docker compose up -d --build
```

默认连接写在 `src/main/resources/application.yaml`，可以用环境变量覆盖：

- `CITYGO_DB_HOST`、`CITYGO_DB_PORT`、`CITYGO_DB_USERNAME`、`CITYGO_DB_PASSWORD`
- `CITYGO_REDIS_HOST`、`CITYGO_REDIS_PORT`
- `CITYGO_RABBITMQ_HOST`、`CITYGO_RABBITMQ_PORT`、`CITYGO_RABBITMQ_USERNAME`、`CITYGO_RABBITMQ_PASSWORD`
- `CITYGO_UPLOAD_DIR`
- `CITYGO_ADMIN_USER_IDS`：逗号分隔的管理员用户 ID，默认空名单拒绝商户新增/修改和发券
- `CITYGO_DEMO_SECKILL_INIT`：本机默认 false、Compose 为 true，仅安全初始化空白演示秒杀券 10
- `CITYGO_SHOP_CACHE_MAX`、`CITYGO_SHOP_CACHE_TTL`：本地详情缓存默认 1000 条、60 秒；这两个环境变量可用于本机应用，当前 Compose 未传入

```text
mvn spring-boot:run
```

本机启动时应用端口 8081。静态页面在 `src/main/resources/nginx-1.18.0/html/citygo`，底子是黑马点评课程页面。这里改了名称，页面换成统一样式，演示商户和笔记换成广州数据，并接上评论、收藏、我的订单和模拟支付。页面请求走相对路径 `/api`，不写死后端地址。`nginx-1.18.0/conf/nginx.conf` 给 Windows 上的 `nginx.exe` 用，把 `/api` 转到本机 8081。Compose 用的是 `deploy/nginx.conf`，转到容器里的应用。

改 `citygo.seckill.unpaid-timeout` 之前，要先删掉 RabbitMQ 里的 `seckill.order.delay`，否则启动会报队列参数不一致。

演示 SQL 只写 MySQL。Compose 启动后，初始化器只在券 10 已上架、SQL 库存为初始 20、没有历史订单、Redis 库存/时间/资格均不存在时原子写入秒杀 key。已有库存或部分 key 时跳过，不会重置正在运行的库存；也不能用它修复已有活动的 Redis 数据丢失。已有部署需先重新构建新应用，`restart` 不会更新代码。上线检查步骤见 `deploy/V1_RELEASE_CHECKLIST.md`。

## 验证记录

2026-10-12 22:37（北京时间）在修复后的工作区运行全量 Maven 测试：11 个测试类、41 个测试，零失败/错误/跳过，Java 21、本机 MySQL/Redis，命令 `mvn -B -Dstyle.color=never -DforkCount=0 test`，日志 `target/v1-release-test.log`。Node 前端参数编码/解码 5 组案例通过，命令 `node src/test/js/common.test.js`。22:38 的 `mvn -B -Dstyle.color=never -DskipTests package` 打包通过，产物 `target/citygo-1.0-SNAPSHOT.jar`，日志 `target/v1-release-package.log`。这些是未提交工作区的证据，不是原 HEAD 的验证结果。

多数 Maven 流程测试关闭 Web、MQ 自动消费和定时调度，直接调用业务方法。随后完成本机 Java 8 镜像构建/运行、全新 Compose 初始化、36 项真实 HTTP 检查、完整服务重启、中文/图片和并发点赞检查，MQ 消费及定时扫描保持开启。23:44 实际等待 900 秒验证秒杀延迟取消，SQL/Redis 库存和资格恢复，再次抢购及模拟支付通过；普通订单扫描兜底也已独立验证。容器验收还发现并修复了 SQL 导入中文乱码；详见 `deploy/V1_CONTAINER_ACCEPTANCE.md`。Dockerfile 跳过测试，构建成功不代表测试通过；尚未覆盖全部 MQ 故障注入、公网部署或性能测量。

## 仓库

当前代码在 `citygo-dev` 分支：https://github.com/tiao46086-cell/citygo/tree/citygo-dev
