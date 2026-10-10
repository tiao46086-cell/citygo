# CityGo

本地生活服务。在黑马点评的商户、登录和优惠券模型上，做了缓存更新和优惠券秒杀。

课程来源：[黑马程序员 Redis 入门到实战](https://www.bilibili.com/video/BV1cr4y1671t)。缓存旁路和 Lua 秒杀的思路来自课程，下面列的是这个仓库里实际写了的行为。

## 已经完成

- 登录：验证码放在 Redis，登录后换成 token。请求先取出用户，未登录的接口返回 401。
- 商户详情：Cache Aside，空值缓存，Redisson 互斥重建，过期时间加随机偏移。更新时数据库提交后再删缓存，1 秒后再删一次。
- 秒杀：先生成订单号，再用 Lua 判断活动时间、库存和一人一单，并记下这次预占的订单号。通过后把消息发到 RabbitMQ，等发布确认。
- 下单消费者：手动应答。事务提交后再 ack。同一个订单号再投递不重复扣库存。数据库库存扣失败时，按订单号回补 Redis。
- 消费失败最多处理 3 次，中间隔 5 秒。仍然失败的消息进 `seckill.order.fail`，不再立刻入队。
- 未支付订单 15 分钟后取消。先改数据库并退回 MySQL 库存，提交之后再按订单号回补 Redis。取消后同一个人可以再抢。
- 支付只把未支付且未超时的订单改成已支付，不接入支付宝或微信，也不再扣库存。
- `GET /voucher-order/{id}` 查询处理中、未支付、已支付、已取消、已超时。
- 发布确认超时留下的预占，每 5 秒检查一次。空闲超过 10 秒还没有订单，就按原来的订单号再发一次。库里已经有订单就删掉预占标记。

没有做：评论、收藏、评分、关注 Feed、GEO、签到、Elasticsearch、Caffeine、压测数字。

## 本地运行

需要本机 MySQL（库名 `hmdp`，先导入 `hmdp.sql`）、Redis，以及 RabbitMQ。本仓库用 Docker 只跑 RabbitMQ：

```text
docker run -d --name citygo-rabbitmq -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=citygo -e RABBITMQ_DEFAULT_PASS=citygo rabbitmq:3-management
```

默认连接写在 `src/main/resources/application.yaml`，可以用环境变量覆盖：

- `CITYGO_DB_USERNAME`、`CITYGO_DB_PASSWORD`
- `CITYGO_REDIS_HOST`、`CITYGO_REDIS_PORT`
- `CITYGO_RABBITMQ_USERNAME`、`CITYGO_RABBITMQ_PASSWORD`
- `CITYGO_UPLOAD_DIR`

```text
mvn spring-boot:run
```

应用端口 8081。静态页面在 `src/main/resources/nginx-1.18.0/html/hmdp`，Nginx 配置把 `/api` 转到 8081。`nginx.exe` 只是 Windows 上起页面用的，不是项目本身。

改 `citygo.seckill.unpaid-timeout` 之前，要先删掉 RabbitMQ 里的 `seckill.order.delay`，否则启动会报队列参数不一致。

## 仓库

当前代码在 `citygo-dev` 分支：https://github.com/tiao46086-cell/citygo/tree/citygo-dev
