# CityGo 项目开发上下文

## 1. 目标、协作与核对基准

2026-10-13 最终本地发布候选核对：当前工作区绝对路径为 `D:\citygo\hmdp`，分支以 Git 实际状态为准；Java 41 项、Node 3 个脚本和独立 Compose 40 项 HTTP 检查通过。公网服务器、域名和 HTTPS 尚未提供，不能标记公网上线。

CityGo 基于黑马点评学习项目，目标是完成 **v1.0**：理解原有实现，完成当前业务范围的工程化改造，并能独立解释调用链和取舍。不再规划 v1.1，不主动增加技术栈。已有代码不等于已验证，也不等于 v1.0 已验收完成。

协作规则见根目录 `AGENTS.md`：先解释再修改，较大修改先确认，从 Java 基础语法讲解，一次推进可理解的一小块。

本次代码核对日期：2026-10-13（北京时间）。核对时的状态：

- Git 分支：`citygo-dev`；项目绝对路径以当前工作区和 `git rev-parse --show-toplevel` 为准。
- 迁移前提交：`eb6eaf19ce268e0f3d391fb0225372d895c9f6c0`；命名迁移提交以 Git 实际状态为准。
- 说明：v1.0 订单补偿修复和容器部署验收。
- 提交时间：2026-10-13 09:46:12 +08:00。

这是核对快照，不是未来会话的实时状态。继续工作时检查实际 Git 状态；以代码、SQL、配置、测试和运行报告为事实来源，旧 Phase 总结不能覆盖这些证据。

2026-10-13 完成项目命名迁移：Java 包和源码测试目录为 `com.citygo`，页面目录为 `html/citygo`，基础 SQL 为 `citygo.sql`，应用名、Compose 挂载和 Nginx root 与之对应。随后当前配置中的数据库名统一为 `citygo`；脚本备份文件名也改为 `citygo-*.sql`。这次是命名和引用迁移，不是新业务功能。

## 2. 功能来源与当前范围

“原仓库已有”“黑马课程讲过”“CityGo 新增或重构”必须分开。初始提交 `bd50502` 中不少 Service 是空壳，课程完整版本的功能不能直接算作本仓库原有实现。

| 分类 | 内容 |
| --- | --- |
| 原仓库已有基础 | 商户增改查、分类分页、名称 LIKE 搜索；笔记发布、我的笔记、按点赞排序的热门列表、简单点赞加计数；优惠券模型、查询与新增入口；课程页面和数据库基础模型 |
| 课程已有思路，当前仓库后来补齐或改造 | Redis token、Cache Aside、空值缓存、互斥重建、Redis 分布式 ID、Lua 秒杀、ZSet 点赞；不能宣称为原创思想 |
| CityGo 新增或工程化改造 | RabbitMQ 异步下单及确认/应答、按订单号预占和补偿、创建与终止裁决、重试和恢复扫描、超时取消、普通券交易、模拟支付、一级评论、笔记收藏、体验账号管理、管理员校验、Caffeine 与提交后双删、笔记编辑删除及校验、上传路径和归属校验、页面改造和 Compose 部署 |

当前有实现代码的业务：商户及分类、名称搜索、笔记、点赞、一级评论、笔记收藏、验证码/体验登录与退出、普通券/秒杀券、我的订单、订单查询、模拟支付、超时取消与库存回补。

以下未实现，属于历史设想或 v1.0 范围外，不自动形成待开发清单：

- 评论回复/点赞、商户收藏、用户评分及评分统计。
- 关注、Feed、GEO 附近商户、签到、综合热度排行。
- Elasticsearch、真实短信、真实支付宝/微信支付、核销、退款。
- 性能压测及 QPS、平均响应时间、P95、P99；本地消息表、全站日志规范改造。

热门笔记已有按 `liked` 排序的查询，商户搜索已有 SQL LIKE；综合热度排行和全文搜索未实现。评分、经纬度等字段，关注/签到表，Redis 常量或空 Controller 不代表业务已实现。

## 3. 实际技术栈与入口

- Java 8、Spring Boot 2.7.4、Spring MVC、MyBatis-Plus 3.5.2、MySQL、Redis/Lua、RabbitMQ。
- Redisson 3.17.7：商户缓存重建、体验账号分配/绑定、同一用户对同一笔记的点赞切换；依赖为 `redisson`，不是 starter。秒杀资格由 Lua 判断，不使用上述锁。
- Caffeine：商户详情本地缓存，版本由 Spring Boot 依赖管理。
- Docker/Compose、Nginx；前端沿用课程 Vue/Element/axios 基础并改造。

应用名 `citygo`，启动类 `CityGoApplication`，包名 `com.citygo`，库名 `citygo`，应用端口 8081，Maven 版本 `1.0-SNAPSHOT`。配置在 `src/main/resources/application.yaml`。

不主动引入 Elasticsearch、Spring Cloud、Dubbo、Kafka、RocketMQ、分库分表、XXL-JOB、Canal、Sentinel、Kubernetes 等。已有 Caffeine、Docker、Nginx 不意味着继续扩展技术栈。

## 4. 登录、权限与体验账号

入口：`UserController` → `UserServiceImpl`；请求身份由 `RefreshTokenInterceptor` → `LoginInterceptor` 处理。

- `POST /user/code` 将验证码按手机号存 Redis，TTL 2 分钟，在日志中模拟发送。`POST /user/login` 校验后删除验证码并签发 token；没有真实短信或 JWT 双 token。
- token 为 Hash `login:token:{token}`。刷新拦截器 order 0，读取身份、写 `UserHolder`，请求结束清理 ThreadLocal；登录拦截器 order 1，无身份返回 401。
- 普通登录续期 36000 分钟（25 天）；体验 token 带 `demo=1`，续期 120 分钟（2 小时）。
- 白名单包括验证码/登录/体验登录、热门笔记与详情、点赞用户列表、评论列表、商户/分类和券列表。以 `MvcConfig` 路径匹配为准，不能概括成“白名单全是只读接口”。
- `POST /user/logout` 删除当前 token，个人页面同时清理浏览器 token。
- 商户新增/修改和发券使用 `AdminGuard`。`CITYGO_ADMIN_USER_IDS` 是逗号分隔的用户 ID，默认空名单拒绝操作；没有完整商家角色模型。

`POST /user/demo` 分配体验用户：手机号以 190 开头，不能走现有验证码手机号校验；同一 IP 10 分钟最多 10 次，体验账号上限 100。分配和 token 绑定在 Redisson 锁保护下完成，避免两个访客拿到同一账号。

名额满后只回收无登录态且无在途秒杀的账号：取消未支付订单并回补库存，清理笔记/评论/收藏/点赞和上传记录、秒杀资格；已支付订单不退库存。保留订单行，以新订单号写 `tb_user.demo_order_after` 隐藏旧订单，保持消息重投的主键幂等性。

## 5. 商户缓存与搜索

入口：`ShopController` → `ShopServiceImpl` → `ShopMapper`。

- 详情先读 Caffeine，再读 Redis，最后查 MySQL。本地默认最多 1000 条，写入后 60 秒过期，开启统计，不缓存空值。
- Redis `cache:shop:{id}`：正常 TTL 30 分钟加 0～4 分钟随机抖动；查不到写空串，TTL 2 分钟。
- 重建锁 `lock:shop:{id}` 使用不设租期的 `tryLock()` 和看门狗；抢锁后再查缓存，解锁前确认当前线程持有情况。
- 没抢到每 50 毫秒重查，约 500 毫秒仍未获得则抛“店铺正在重建，请稍后重试”。这是等待上限，不是整个查询的耗时保证。
- 更新有事务，ID 为空或未更新到行时失败；提交后分别删本地/Redis 缓存，异常记录日志，1 秒后守护线程再次删除。删缓存失败不会回滚已提交的数据库。
- 延迟删除保存在进程内，退出会丢失。本地不广播跨实例失效，其他实例依赖本地 TTL；不能宣称强一致性。
- 分类列表整份存 `cache:shop:type:`，TTL 30 分钟，没有套详情的本地缓存和锁。
- 名称搜索是 SQL LIKE，分类是数据库分页，没有 GEO 或全文搜索。

## 6. 笔记、评论、收藏与上传

入口：`BlogController`、`BlogCommentsController`、`BlogFavoriteController`、`UploadController` 及对应 Service/Mapper。

- 笔记支持发布、详情、我的笔记、修改/删除自己的；服务端设置作者、计数和 ID/时间，校验标题、正文、商户和图片长度。删除同时清理评论、收藏和 Redis 点赞 key。
- 热门按 `liked` 排序，作者批量 `listByIds` 查询，消除原有 N+1。
- 点赞使用 `blog:liked:{blogId}` ZSet，支持点赞/取消、当前用户状态、最近点赞用户；根据成员实际增删更新计数。同一用户同一笔记用 Redisson 锁串行处理；SQL 抛异常时撤销 Redis 增删，取消失败恢复原 score。补偿本身也可能失败，异常保留为 suppressed exception，不保证跨库强一致。这是课程思路上的补齐和修复。
- 评论只支持一级发布、分页、删除自己的；正文去空白后非空且不超过 255 字符，parentId/answerId 固定 0；增删维护计数，并发删除只有实际删行的一方减计数。回复/点赞字段不是功能证据。
- 笔记收藏支持添加、取消、状态和我的收藏分页；`tb_blog_favorite.uk_user_blog` 防止同一用户同一笔记重复。
- 上传使用配置目录，检查后缀和目录内路径；删除检查当前用户 Redis 上传记录和目标路径。此模块未建立完整自动化测试证据。
- 页面已接评论、收藏、体验登录/退出、普通券、秒杀查询、我的订单和模拟支付；页面代码存在不等于端到端验收通过。

## 7. 秒杀与失败处理

调用链：`VoucherOrderController` → `seckillVoucher` → Redis ID/Lua → RabbitMQ → `SeckillOrderListener` → 事务代理 `createVoucherOrder` → MySQL。

1. 先用 `seckill_limit.lua` 限流：`seckill:limit:{userId}`，1 秒最多 5 次。
2. 先生成订单号再执行秒杀 Lua。`RedisIdWorker` 用 `Instant.now()` 读取真实 UTC 时刻，减去 2022-01-01 UTC 基准后左移 32 位，再或上 UTC 日期的 Redis INCR 序号，生成候选号。每次读取 SQL 最大订单号和最大体验分界，再由 `id_monotonic.lua` 原子选出高于历史/Redis 下界的新号；大整数用字符串比较、INCR 后 GET，避免 Lua 浮点精度损失。兼容旧版北京时间 ID，不重写旧订单；切换期新 ID 高位不一定对应当前秒。每次发号增加一次 SQL 查询，未做性能测量；升级时应停止旧版实例，不混用发号算法。主键为 `IdType.INPUT`。
3. Lua 使用 Redis `TIME` 检查时间、库存、资格：0 通过、1 无库存、2 重复、3 未开始、4 已结束、5 券数据不存在。
4. 通过则减库存，写 `seckill:hold:{voucherId}` Hash（用户 ID → 订单号）和 `seckill:pending:{orderId}` Hash（userId、voucherId、state=waiting、nextRetryAt）。旧 `seckill:order:{voucherId}` Set 只兼容历史资格。
5. JSON 消息发至持久队列 `seckill.order.queue`，默认交换机、队列名路由，correlated confirm、mandatory/returns，最多等 3 秒。

明确连接/认证失败或 broker 拒绝/退回时，按订单号回补并返回失败；确认超时/中断/异常或发送结果不确定时返回订单号字符串并保留预占，供查询和补发。返回订单号不保证已经入库，不能宣称消息零丢失。

事务创建先 `INSERT IGNORE` 占订单号（status=0），再条件更新 0 为未支付（1）；终止争抢同一行改成失败（7）。创建赢了才条件扣 MySQL 秒杀库存；终止赢了后重投不能再建单或扣库存。

| 业务返回值 | 含义 |
| --- | --- |
| CREATED | 创建成功，事务提交后监听器 ack |
| EXISTS | 已有同一订单，不再扣库存 |
| TERMINATED | 已失败终止，不再创建 |
| STOCK_FAILED | 数据库库存不足，保留 status=7 失败行；提交后回补 Redis，不允许迟到消息重建 |
| INVALID | 缺字段，直接应答 |

创建/取消异常通常经重试队列间隔 5 秒再投，首次加两次重试共 3 次。转发确认收到后才应答原消息；状态标记、补偿或转发确认失败可能 nack 重新入队，不能保证所有故障严格只执行 3 次。

下单重试耗尽时，`abandonFailedCreate` 先裁决失败、按订单号退预占，查询返回失败，再尝试转 `seckill.order.fail`。若创建已赢，保留 hold 和库存，只删 pending。终止完成而失败队列未确认收到时会 ack 原下单消息，因此不能保证失败队列留有每条失败消息；失败队列没有自动消费者。

失败订单回补在 SQL 提交后执行；回补异常保留 pending，失败终态重投和扫描都会继续恢复，即使 pending 是 retrying。`seckill_restore.lua` 只有 hold 的订单号匹配原订单号才加库存、删 hold、删 pending。先成功 INCR，再删除资格，因为 Lua 报错不会自动撤销已执行命令。库存 key 缺失或非整数时报错，保留资格供排查，不凭一次回补重建整个活动库存。重复执行或旧单遇新预占返回 0，不误退新订单。

## 8. 普通券、生命周期与恢复扫描

普通券 `POST /voucher-order/{id}` 同一事务内检查类型/上架状态、条件扣 `tb_voucher.stock` 并建未支付订单；不走秒杀预占，不限制一人一单。秒杀库存仍在 `tb_seckill_voucher`。新增券校验参数并强制正确类型，普通券另行写库存列。

| 数据库状态 | 用途 |
| --- | --- |
| 0 | 秒杀创建和终止尚未裁决 |
| 1 | 未支付 |
| 2 | 已支付（模拟支付） |
| 4 | 已取消 |
| 7 | 下单失败，保留终止结果 |

“已超时”是 status=1 且超过期限的展示，不是独立数据库状态。SQL 注释仍列 1～6，代码使用 0、7；3/5/6 的注释不代表核销/退款已实现。

- `GET /voucher-order/{id}` 校验归属和体验分界，查询处理中、下单失败、未支付、已支付、已取消、已超时。
- `GET /voucher-order/me` 分页，排除 0、7 及体验分界以前的订单，批量补券和商户信息。
- `POST /voucher-order/pay/{id}` 仅将当前用户未超时的 status=1 改 2，写 `pay_time=NOW()`，不接第三方支付、不再扣库存；期限使用数据库 NOW()。
- 建单发取消消息到 `seckill.order.delay`，默认 TTL 900000 毫秒（15m），经 `seckill.order.dlx` 到 `seckill.order.cancel`。支付和取消条件修改同一行 status=1。
- 秒杀取消事务改状态、回补 MySQL 库存并记 Redis 回补任务，提交后按订单号回补 Redis。普通券只回补 `tb_voucher.stock`；重复取消不重复加库存。
- 秒杀创建中的取消消息明确失败使创建事务回滚，确认超时只记日志；普通券捕获取消消息发送异常、保留订单，由扫描补救。

`SeckillPendingScheduler` 默认每 5 秒：

1. 扫 pending：数据库 status=7 时优先重试回补，不因 retrying 丢弃恢复入口；waiting 到 nextRetryAt 才补发同一单（默认间隔 10 秒）；其他已入库订单删 pending，retrying 不补发，历史 stopped 做终止补偿。查询只读不推迟重试；兼容旧字符串并转 Hash。
2. 扫 `seckill:restore:{orderId}`，重试已取消订单的 Redis 回补。任务先于 SQL 提交被看到时保留；事务最终回滚的残留任务可能长期保留，需要核对后处理。
3. 每轮取最多 20 个超时未支付订单，取消及回补，补救延迟消息缺失。

这些是恢复机制，不是跨 MySQL/Redis/MQ 原子事务或零丢失保证。扫描异常记日志，应用停机期间调度不运行。

## 9. 数据库与部署事实

运行步骤见 `README.md`。以下配置已进行本机独立 Compose 验收，具体覆盖与边界见 `deploy/V1_CONTAINER_ACCEPTANCE.md`，不等于公网部署通过。

- 新建库按 `citygo.sql` → `sql/citygo-upgrade.sql` 执行，需要演示数据再执行 `sql/citygo-demo.sql`。三份脚本分别声明 `SET NAMES utf8mb4`，避免 Docker 每份脚本独立连接导致演示中文乱码。升级脚本按列存在性增加普通券 stock 和 demo_order_after，可独立用于已有库。只导入基础 SQL 不满足当前实现。
- 演示数据为广州虚构商户/笔记，坐标是示意点，图片不是虚构门店实拍；填普通券初始库存，插入秒杀券 10。
- 演示 SQL 不自动写 Redis。`DemoSeckillInitializer` 由 `CITYGO_DEMO_SECKILL_INIT=true` 开启（本机默认关闭、Compose 开启）：只初始化上架秒杀券 10，要求 SQL 库存仍为初始 20、无历史订单，且 Redis stock/time/hold/旧资格全部不存在。Lua 原子检查并写入，不覆盖现存或部分数据；不用于已有活动丢失 Redis 后的恢复。活动时间固定按 `Asia/Shanghai` 转 epoch，不依赖 JVM 默认时区。
- Dockerfile 使用 Maven/Java 8 构建、Java 8 JRE 运行，启动固定 `-Duser.timezone=Asia/Shanghai`；`maven.test.skip=true` 跳过测试，镜像构建不证明测试通过。
- Compose 包含 MySQL 8.0.34、Redis 7.2、RabbitMQ 3.13-management、应用和 Nginx 1.27；只暴露 Nginx 宿主机 8080，数据库/Redis/MQ 用内部网络。
- MySQL 空卷按三份 SQL 初始化，已有卷不重跑；Redis AOF，RabbitMQ 固定节点名 rabbit@citygo-rabbitmq，三者有命名卷。
- 上传目录 /data/imgs 映射页面图片目录；Nginx 将 /api/ 代理至 app:8081，本机 Nginx 配置则转 127.0.0.1:8081。
- `deploy/import-demo.ps1` 先备份、再执行升级/演示 SQL、删商户缓存和锁 key、重启应用清本地缓存；重启后按上述门控初始化演示秒杀。不为重跑初始化删除已有数据库卷。已有容器需要先用新代码重新构建，单纯 restart 不会更新程序。
- 修改 unpaid-timeout 会改变队列参数，需处理旧 `seckill.order.delay` 后重建，否则报参数不一致；不能未经确认删除队列。

## 10. 测试代码与运行证据

分别记录有实现代码、有测试代码、某次运行通过，不能合并为“已完成”。

以下为文档审计时保留的历史记录。2026-10-12 22:37 在修复后的未提交工作区重新运行了完整测试：11 个测试类、41 个测试，零失败/错误/跳过，耗时 16.863 秒，Java 21，命令 `mvn -B -Dstyle.color=never -DforkCount=0 test`，日志 `target/v1-release-test.log`。原 HEAD 未包含本轮修复，不把此次结果绑定到旧提交。

| 测试类 | 覆盖 | 历史报告通过数 | 文件时间（北京时间） |
| --- | --- | --- | --- |
| AdminGuardTest | 名单允许/拒绝、解析 | 3 | 2026-10-10 18:13:16 |
| BlogCommentFlowTest | 归属、并发删除计数、发布计数初始化 | 3 | 2026-10-12 17:09:27 |
| BlogFavoriteFlowTest | 收藏/取消/分页、用户隔离 | 1 | 2026-10-10 21:57:49 |
| BlogLikeFlowTest | 点赞切换、用户隔离 | 1 | 2026-10-10 23:01:06 |
| SeckillLuaTest | 回补幂等、不误退新预占 | 2 | 2026-10-10 17:47:22 |
| ShopLocalCacheTest | 本地命中、更新失效、空值 | 1 | 2026-10-10 22:07:35 |
| VoucherCreateFlowTest | 券库存、类型、参数 | 4 | 2026-10-12 17:09:28 |
| DemoAccountRepairTest | 并发绑定、续期、点赞回收 | 4 | 2026-10-12 17:09:28 |
| SeckillOrderFlowTest | 重投幂等、支付取消竞争、普通券、创建终止竞争、回收后重投 | 11 | 2026-10-12 18:04:59 |

历史 9 份报告合计 30 个测试，来自不同时间；另有 JVM dump/崩溃日志，不能宣称历次测试都成功。本轮新增：SeckillOrderFlowTest 3 项（库存失败终态、回补失败后的重投/扫描、提交前任务保留）；BlogLikeFailureTest 3 项（模拟 SQL 异常和无更新行的补偿）；ReleaseInfrastructureTest 5 项（时区、UTC 候选号、长整数与历史分界、并发 ID、初始化防覆盖）。这些测试代码已实际运行通过；失败回补用非整数库存触发真实 Redis 错误，未模拟进程崩溃。

Node 内置 VM/断言运行 `node src/test/js/common.test.js`，5 组参数编码/解码案例通过，包含中文、&、+、#、/、%、0、false 和空值省略。22:38 执行 `mvn -B -Dstyle.color=never -DskipTests package` 成功，JAR 为 `target/citygo-1.0-SNAPSHOT.jar`；打包日志 `target/v1-release-package.log`，此命令跳过测试，测试证据来自前述独立运行。

AdminGuardTest、BlogLikeFailureTest 为单元测试；SeckillLuaTest 和部分 ReleaseInfrastructureTest 直接连本机 Redis。Spring 流程测试主要连本机 MySQL/Redis，WebEnvironment.NONE，关闭 MQ 自动消费和调度，直接调用业务方法；新增扫描回补案例直接调用扫描方法。方法发送过消息也不等于验证完整消费者、重试和死信链路。

已确认本机 Docker Desktop 4.94.0、Docker Engine 29.8.2、Compose v5.5.1 可用；此前命令不可访问是沙箱权限限制，不是未安装。之后实际完成 Java 8 镜像构建/运行、全新 Compose 初始化、36 项 HTTP 功能检查、完整服务重启及补充中文/图片/并发/退出检查；真实 MQ 消费者和定时扫描开启，普通订单扫描兜底已触发。浏览器查看首页、中文搜索正常。具体证据见 `deploy/V1_CONTAINER_ACCEPTANCE.md`。仍未覆盖所有页面操作、MQ 故障注入、所有 Lua 时间/限流分支，未验收公网域名/HTTPS；没有性能数据。上线验收步骤见 `deploy/V1_RELEASE_CHECKLIST.md`。

## 11. v1.0 收尾与对外描述

登录返回修复：公共拦截器遇到 401 清理失效 token，通过 location.replace 替换无权访问的页面，并防止重复跳转和登录页自跳转；个人页不再额外跳转，请求失败结束加载并提示错误。未登录访问“我的”仍进入登录页，没有新增游客个人页。各页面 common.js 版本升至 9。Node 执行 `src/test/js/login-navigation.test.js`，使用实际拦截器与个人页脚本验证 401、重复 401、登录页和 500 错误分支，模拟历史记录返回一步到首页；原参数测试 5 组也通过。此为脚本验证，未进行本次真实浏览器返回缓存或容器端到端验收。

2026-10-13 经确认完成五项小残留清理：登录 Controller、Service 接口与实现不再传递未使用的 HttpSession；删除 UserController 未使用的日志注解、common.js 旧 IP 注释、Windows Nginx 未使用的 upstream backend 和默认欢迎页 html/index.html。实际代理仍指向本机 8081，html/citygo 首页与 50x 错误页保留。11:39:29 在 Java 21、独立 citygo 测试库（13306）和 Redis（6379）上执行 `mvn -B -Dstyle.color=never -DforkCount=0 test`，41 项通过；MQ 自动消费与调度按原测试配置关闭。Node 参数测试 5 组通过，项目自带 Windows nginx.exe 的 `-t` 配置检查通过；本次未做 HTTP 登录或容器端到端验收。日志 `target/cleanup-small-tests.log`。

2026-10-13 后续经确认清理：删除没有页面入口且依赖未实现 `/user/{id}`、`/blog/of/user` 接口的 `other-info.html`，删除后端不支持的密码登录页 `login2.html`、未引用的 ScrollResult、空 SeckillVoucherMapper XML，移除 LoginFormDTO.password 与无引用的共同关注 CSS；Java Mapper、UserInfo 和数据库表保留。11:32:27 在 Java 21、独立 citygo 测试库（13306）和 Redis（6379）上执行 clean test，41 项全部通过；MQ 自动消费和调度按既有测试配置关闭。Node 参数测试 5 组、保留页面 9 段内联 JavaScript 语法检查通过，未做本次浏览器或容器验收。原始日志 `target/cleanup-final-tests.log`。

2026-10-13 经确认清理未使用课程代码：删除关注模块 Controller/Service/Mapper/Entity 空壳、未引用的 RedisData 和 PasswordEncoder，移除 Feed/GEO/签到常量及未调用的邮箱/验证码校验方法和密码正则。手机号校验、UserInfo 页面查询和 SQL 表保留。随后经确认移除 `other-info.html` 的关注按钮、共同关注页签、相关状态/方法/接口请求，以及不再使用的登录用户查询；用户资料和笔记展示保留。页面内联 JavaScript 语法检查通过，未进行浏览器全页面验收，也不能宣称已完成全仓库无用代码清理。11:10:05 在 Java 21、独立 citygo 测试库（宿主 13306）、Redis 6379 上执行 `mvn -B -Dstyle.color=never -DforkCount=0 clean test`，41 项通过；Spring 流程测试按既有配置关闭 MQ 自动消费和调度，不代表本次完成容器或端到端验收。Node 参数测试 5 组通过。该次日志曾保存在 `target/cleanup-tests.log`，后续 clean 已清除，历史结论保留在本文。

2026-10-13 后续数据库命名收尾：当前配置、SQL 和导入脚本默认库名统一为 `citygo`，Compose 项目名固定为 `citygo`，移除含旧课程页面和内嵌 Git 仓库的重复 Nginx 压缩包。先备份独立验收数据库，再复制到 `citygo`，Java 21 的 41 项测试全部通过（10:36:40，宿主 MySQL 13306）；Node 5 组通过。另起 `citygo-schema-check` 空卷，三份 SQL 初始化成功，应用连接新库，在 18083 端口的 13 项 HTTP/MQ 检查通过。此空库只有 `citygo` 业务库。原部署、原备份、学习资料、Git 历史和本地工作区目录尚未清理，不能宣称机器与历史记录中的旧名称已经全部消失。切换旧部署须先备份、迁移并核对数据，不能直接换卷后视作升级成功。

2026-10-13 命名迁移后的验证：Java 21 执行 `mvn -B -Dstyle.color=never -DforkCount=0 test`，在独立 Docker MySQL（宿主 `13306`）、Redis（`6379`）和 RabbitMQ 环境中 41 项全部通过；独立库预置了测试代码所需的用户 1013、1014，不能据此推断生产库包含这些用户。Node 参数测试 5 组通过。Java 8 Dockerfile 镜像构建成功；新包名应用和 `html/citygo` 页面启动后，首页、静态资源、图片、商户/分类/券查询、体验登录、普通下单、秒杀消息入库、模拟支付、退出共 13 项 HTTP 检查通过。旧数据库名 `citygo`、旧数据卷未迁移或删除。

本机容器验收于 2026-10-12 23:44:58 完成：真实等待约 900 秒，由 Rabbit 取消监听器消费延迟死信消息，SQL/Redis 库存和购买资格恢复；取消后重抢经 MQ 入库并模拟支付通过。与普通订单前移时间的扫描检查分别记录，不混淆真实 TTL 和加速测试。空库中文乱码已用 SQL 显式 SET NAMES 修复并重新初始化验证。详见 `deploy/V1_CONTAINER_ACCEPTANCE.md`。当前可以作为 v1.0 正式部署候选，修复已提交并推送 GitHub；尚未完成公网验收或标签发布。

当前范围有实现代码，运行验证与验收状态需另行记录。后续只围绕已有范围核对运行、修复经确认的问题、补必要验证和讲解；已知缺口不是自动授权的新任务。

可描述实际新增/重构和自己能解释的调用链，明确课程来源与验证范围。不能写原创缓存/Lua 思路、消息零丢失、强一致缓存、完整商家角色、真实支付、未实现模块、未运行的测试通过、未测量的性能提升。

讲解按概念 → 基础语法 → 关键代码 → 调用过程 → 小例子 → 易混淆处，解释原问题、方案理由和新问题，优先保证开发者理解。
