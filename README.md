# CityGo

本地生活服务。在黑马点评的商户、登录和优惠券模型上，做了缓存更新和优惠券秒杀。

课程来源：[黑马程序员 Redis 入门到实战](https://www.bilibili.com/video/BV1cr4y1671t)。缓存旁路和 Lua 秒杀的思路来自课程，下面列的是这个仓库里实际写了的行为。

## 已经完成

- 登录：验证码放在 Redis，登录后换成 token。登录页的「体验 CityGo」由后端分配体验用户，并返回同样的 token。体验用户手机号以 190 开头，不能走验证码登录。同一 IP 10 分钟最多 10 次，体验用户最多 100 个，满了不再复用旧账号。不接真实短信。未登录的接口返回 401。
- 商户详情：先查本机 Caffeine，未命中再走 Cache Aside。空值只放 Redis。Redisson 互斥重建，过期时间加随机偏移。更新时数据库提交后再删这两级缓存，1 秒后再删一次。单机内存，不向其他实例广播失效。
- 一级评论：发布、分页、删除自己的。笔记收藏：收藏、取消、我的收藏列表。同一用户对同一篇笔记只有一行。点赞同一个人只记一次，再点取消。
- 秒杀：先生成订单号，再用 Lua 判断活动时间、库存和一人一单，并记下这次预占的订单号。通过后把消息发到 RabbitMQ，等发布确认。
- 下单消费者：手动应答。事务提交后再 ack。同一个订单号再投递不重复扣库存。数据库库存扣失败时，按订单号回补 Redis。
- 消费失败最多处理 3 次，中间隔 5 秒。仍然失败的消息进 `seckill.order.fail`，不再立刻入队。
- 未支付订单 15 分钟后取消。先改数据库并退回 MySQL 库存，提交之后再按订单号回补 Redis。取消后同一个人可以再抢。
- 支付只把未支付且未超时的订单改成已支付，不接入支付宝或微信，也不再扣库存。
- `GET /voucher-order/me` 分页返回当前用户已经入库的订单。`GET /voucher-order/{id}` 查询处理中、未支付、已支付、已取消、已超时。
- 发布确认超时留下的预占，每 5 秒检查一次。空闲超过 10 秒还没有订单，就按原来的订单号再发一次。库里已经有订单就删掉预占标记。

没有做：评分、关注 Feed、GEO、签到、Elasticsearch、压测数字。

## 本地运行

需要本机 MySQL（库名 `hmdp`，先导入 `hmdp.sql`）、Redis，以及 RabbitMQ。只把 RabbitMQ 放进 Docker 时：

```text
docker run -d --name citygo-rabbitmq -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=citygo -e RABBITMQ_DEFAULT_PASS=citygo rabbitmq:3-management
```

整套用 Compose 启动时，MySQL、Redis、RabbitMQ 和应用在同一个网络里，页面由 Nginx 提供，地址是 http://127.0.0.1:8080 。这套不映射 3306、6379、5672，本机原来的数据库和队列可以继续留着。8080 如果已经被本机 Nginx 占用，要先停掉那个进程。Redis 打开 AOF，RabbitMQ 固定节点名 `rabbit@citygo-rabbitmq`，三者都用命名卷。

新的空 MySQL 卷会先导入 `hmdp.sql`，再导入 `sql/citygo-demo.sql`。已经存在的 `citygo-mysql` 卷不会重跑初始化。要给已有库换上广州演示数据，运行 `deploy/import-demo.ps1`：先备份到 `backup/`，再导入演示脚本，删掉 Redis 里的商户缓存，并重启应用以清掉本机 Caffeine。不要删除 `citygo-mysql` 卷。

```text
docker compose up -d --build
```

默认连接写在 `src/main/resources/application.yaml`，可以用环境变量覆盖：

- `CITYGO_DB_HOST`、`CITYGO_DB_PORT`、`CITYGO_DB_USERNAME`、`CITYGO_DB_PASSWORD`
- `CITYGO_REDIS_HOST`、`CITYGO_REDIS_PORT`
- `CITYGO_RABBITMQ_HOST`、`CITYGO_RABBITMQ_PORT`、`CITYGO_RABBITMQ_USERNAME`、`CITYGO_RABBITMQ_PASSWORD`
- `CITYGO_UPLOAD_DIR`

```text
mvn spring-boot:run
```

本机启动时应用端口 8081。静态页面在 `src/main/resources/nginx-1.18.0/html/hmdp`，底子是黑马点评课程页面。这里改了名称，页面换成统一样式，演示商户和笔记换成广州数据，并接上评论、收藏、我的订单和模拟支付。页面请求走相对路径 `/api`，不写死后端地址。`nginx-1.18.0/conf/nginx.conf` 给 Windows 上的 `nginx.exe` 用，把 `/api` 转到本机 8081。Compose 用的是 `deploy/nginx.conf`，转到容器里的应用。

改 `citygo.seckill.unpaid-timeout` 之前，要先删掉 RabbitMQ 里的 `seckill.order.delay`，否则启动会报队列参数不一致。

## 仓库

当前代码在 `citygo-dev` 分支：https://github.com/tiao46086-cell/citygo/tree/citygo-dev
