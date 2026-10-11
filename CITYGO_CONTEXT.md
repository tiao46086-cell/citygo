# CityGo 项目开发上下文

## 1. 项目定位

项目名称：CityGo

CityGo 是基于“黑马点评”学习项目进行深度二次开发的本地生活社区平台。

本项目不是简单修改项目名称，也不是单纯堆叠技术栈。

核心目标：

1. 理解原版黑马点评的实现
2. 找出原有实现存在的问题或可以扩展的地方
3. 对核心模块进行重新设计和工程化改造
4. 学习真实 Java 后端项目中的高并发、缓存、消息队列、搜索等问题
5. 最终形成可以用于 Java 后端求职的完整项目


---

# 2. 开发者当前情况

开发者正在学习 Java 后端。

在协助开发时，不要默认开发者已经理解复杂的 Spring、Redis、MQ 或分布式系统知识。

解释代码时应该：

- 从 Java 基础语法开始
- 说明类的作用
- 说明变量的含义
- 说明方法参数和返回值
- 逐行解释关键代码
- 解释 Controller → Service → Mapper 的调用过程
- 遇到循环、条件、Lambda、泛型、注解等语法时详细解释
- 使用小例子帮助理解
- 区分容易混淆的相似写法
- 不要只告诉开发者“这样写就行”


---

# 3. AI 协作规则（非常重要）

你是本项目的编程助手，而不是自动代写工具。

不要一次性完成整个功能。

每次修改代码之前：

1. 先说明当前要解决什么问题
2. 找到涉及的代码文件
3. 解释原来的代码是怎么工作的
4. 说明原实现存在的问题
5. 给出准备采用的解决方案
6. 得到开发者确认后，再进行较大的代码修改

如果只是很小的、明显的修改，可以直接给出修改方案，但仍需解释原因。


禁止：

- 一次生成大量开发者无法理解的代码
- 为了“高级”而无理由加入中间件
- 无业务需求地使用设计模式
- 无需求地引入微服务
- 无需求地引入分库分表
- 为了简历堆技术名词
- 把原版黑马点评已有功能描述成开发者原创功能


---

# 4. 学习原则

每加入一个技术，都必须能够回答三个问题：

① 原来的方案有什么问题？

② 为什么选择这个技术解决？

③ 引入这个技术以后又产生了什么新问题？

例如：

同步创建秒杀订单
↓
高并发情况下数据库压力较大
↓
引入 RabbitMQ 异步削峰
↓
产生消息丢失、重复消费等问题
↓
继续设计 Confirm / ACK / 重试 / 幂等 / 补偿机制

项目重点是理解这一整条问题链。


---

# 5. CityGo 核心业务

CityGo 最终主要包含三个业务方向。

## 5.1 本地生活

包括：

- 商户
- 商户分类
- 商户详情
- 商户评分
- 附近商户
- 商户搜索
- 优惠券


## 5.2 社区内容

包括：

- 探店内容
- 点赞
- 评论
- 收藏
- 关注
- Feed
- 热门内容


## 5.3 优惠券交易

包括：

- 普通优惠券
- 秒杀优惠券
- 抢购资格判断
- 库存控制
- 异步下单
- 订单状态
- 超时取消
- 库存补偿


---

# 6. 计划技术栈

已经在项目里使用：

- Java
- Spring Boot
- Spring MVC（登录用的两个拦截器）
- MyBatis-Plus（单表用它生成 SQL；联表仍写 MyBatis 的 XML，例如 `VoucherMapper.queryVoucherOfShop`）
- MySQL
- Redis
- Lua
- RabbitMQ
- Redisson 3.17.7。只用于商户缓存重建：`tryLock()` 不设租期，看门狗续期；解锁用 Lua 核对持有者。秒杀不加这把锁。依赖用的是 `redisson`，不是 starter，避免换掉 Lettuce
- Docker（只把 RabbitMQ 跑在容器 `citygo-rabbitmq` 里，映射 5672 和 15672。应用本身没有 Dockerfile）

评估之后明确先不引入。开发者没有再次要求时，不要写这些代码，也不要写进简历：

- Caffeine。没有测到 Redis 变慢，本地缓存还要单独处理多台机器之间的失效。
- Elasticsearch
- JMeter，以及任何没有测过的 QPS、P95、P99
- Nginx。仓库里有课程自带的静态页面，没有为 CityGo 做反向代理。
- 真实的支付宝或微信支付
- 本地消息表

技术栈不是必须全部使用。只有当前实现确实挡住业务时，才引入下一种技术。


---

# 7. 原版黑马点评已有能力

开发过程中必须区分：

“原项目已有功能”

和

“CityGo 自己新增/重构的功能”。

黑马点评原课程涉及的典型内容包括：

- Redis 商户缓存
- 缓存穿透
- 缓存击穿
- 缓存雪崩相关思想
- Redis 分布式 ID
- Lua 秒杀
- Redisson 分布式锁
- Redis Stream 异步秒杀
- 点赞
- 关注
- Feed
- GEO 附近商户
- Bitmap 签到
- HyperLogLog
- ZSet 排行等

不能简单把这些内容重新实现后称为 CityGo 的创新。


---

# 8. CityGo 重点二次开发方向

计划重点改造：

## A. 评论系统

状态：跳过。控制器还是空的。

原先要做的探店评论：

- 一级评论
- 回复
- 评论数量
- 评论点赞
- 评论查询


## B. 收藏系统

状态：跳过。

原先要新增：

- 收藏探店内容
- 收藏商户
- 收藏列表


## C. 商户评分

状态：跳过。

原先要新增：

- 用户评分
- 商户平均评分
- 评分统计


## D. 秒杀系统工程化改造

状态：已完成。细节在第 12 节。不要再改回课程里的 Redis Stream，也不要改回 `synchronized`。

原计划：

Redis + Lua
↓
资格和库存预检查
↓
RabbitMQ
↓
异步创建订单
↓
MySQL

这条链路里已经挡住的问题：

- 超卖
- 一人一单
- 消息重复
- 消费失败之后的重试和 Redis 回滚
- 同一个订单号的重复消费

发布确认超过 3 秒没有回来时，消息有没有进队列是不确定的，Redis 资格也不退。所以不能说消息零丢失。


## E. 订单生命周期

状态：已完成。未支付 15 分钟后取消并归还库存；支付只把状态从 1 改成 2。细节在第 12 节。

订单不只停留在“创建成功”。

考虑：

待处理/待支付
↓
已完成

或

待支付
↓
超时
↓
自动取消
↓
恢复相关库存

具体状态根据项目实际业务设计。


## F. Elasticsearch 搜索

状态：不做。开发者没有再次要求时不要开始。

原先考虑过：

- 商户全文搜索
- 探店内容搜索
- 条件筛选
- 排序

根据实际需要考虑：

- 搜索建议
- 热门关键词


## G. 多级缓存

状态：不做。商户缓存保持 Redis，更新路径用延迟双删。不要加 Caffeine。

原先考虑过：

Caffeine
+
Redis
+
MySQL

重点不是简单使用 Caffeine。

需要理解：

- 本地缓存和 Redis 的区别
- 数据一致性
- 缓存更新
- 缓存失效


## H. Feed / 热门排行升级

状态：不做。评论、收藏、评分也跳过了，没有新的热度数据来源。

原先考虑过：

点赞
收藏
评论
发布时间

等因素如何影响内容热度。

不要简单复制原项目 Feed。


---

# 9. 工程化增强

和简历主线一起已经做了的：

- 秒杀接口限流：同一用户 1 秒最多 5 次，脚本是 `seckill_limit.lua`
- MQ 可靠性：发布确认、mandatory 退回、消费者手动应答
- 消费幂等：同一个订单号重复投递不再扣库存
- 缓存一致性：商户更新先写库再删缓存，1 秒后再删一次
- Docker：只有 RabbitMQ 容器

还没做，也不要主动做：

- 全站日志规范
- 应用本身的 Docker 镜像
- JMeter 性能测试

如果以后开发者明确要求压测，再测 QPS、平均响应时间、P95、P99 和错误率，并且用优化前后的数字比较。现在没有这些数字，文档和简历里都不要写。


---

# 10. 暂时不要加入的东西

除非后期出现明确需求，否则暂时不要主动引入：

- Spring Cloud
- Dubbo
- Kafka
- RocketMQ
- ShardingSphere
- XXL-JOB
- Canal
- Sentinel
- Kubernetes

不要为了项目看起来高级而增加复杂度。


---

# 11. 开发阶段

Phase 0　已完成。获取并运行原版黑马点评。

Phase 1　已完成。理解原项目结构和核心调用链。

Phase 2　已完成。登录、商户缓存读路径、两处小修复、应用改名为 CityGo。

Phase 3　明确跳过。评论、收藏、商户评分。`BlogCommentsController` 仍是空的。

Phase 4　已完成。商户更新先写数据库再删缓存，并做 1 秒延迟双删。读路径在 Phase 2 就已经做了。

Phase 5　已完成。Redis + Lua 判断活动时间、库存、一人一单，再用 Redis 生成订单号。

Phase 6　已完成。资格通过后用 RabbitMQ 异步创建订单。

Phase 7　已完成。发布确认、手动应答、按订单号幂等、数据库扣库存失败时回滚 Redis。

Phase 8　已完成。未支付订单 15 分钟后经死信队列取消，并归还 MySQL 和 Redis 库存。支付接口只改订单状态。

Phase 9　不做。Elasticsearch。

Phase 10　不做。Caffeine + Redis 多级缓存。

Phase 11　不做。Feed / 热门排行。

Phase 12　只做了一部分。RabbitMQ 在 Docker 里，应用没有容器化。

Phase 13　不做。JMeter 压测。没有测过的数字不能写。

另外已经写进代码、单独算一条：秒杀按用户限流，1 秒 5 次。

不要再实现 Phase 3、9、10、11、13，除非开发者明确说要做。一次仍然只改开发者确认过的一小块。


---

# 12. 当前阶段

当前阶段：简历要写的主线已经做完。下一步是投简历，不是继续加中间件。

应用名 `citygo`，启动类 `CityGoApplication`，端口 8081。包名仍是 `com.hmdp`，数据库名仍是 `hmdp`。连接信息在 `application.yaml`。Redis 在本机 6379。RabbitMQ 用 Docker 容器 `citygo-rabbitmq`，管理页是 `http://127.0.0.1:15672`。

## 已经完成

Phase 2 的登录和商户缓存读路径：

- [x] 验证码 `POST /user/code`，登录 `POST /user/login`，当前用户 `GET /user/me`
- [x] 验证码按手机号存在 Redis，登录成功后删除，只能用一次。发给 A 的验证码不能登录 B
- [x] token 存在 Redis Hash。`RefreshTokenInterceptor`（order 0，所有请求）先把用户放进 `UserHolder` 这个 ThreadLocal，`LoginInterceptor`（order 1）发现没有用户就返回 401
- [x] 白名单只放行只读接口：`/user/code`、`/user/login`、`/blog/hot`、`/shop-type/**`、`/shop/*`、`/shop/of/*`、`/voucher/list/*`。改商户、下单要登录。还没有区分商家和普通用户
- [x] 商户详情 Cache Aside：`cache:shop:{id}`，TTL 30 分钟再加 0 到 5 分钟随机抖动
- [x] 穿透：查不到时写空串，TTL 2 分钟
- [x] 击穿：Redisson 锁 `lock:shop:{id}`。`tryLock()` 不设租期，默认看门狗约 30 秒过期、约 10 秒续一次。没抢到就睡 50 毫秒再查。抢到后先再读一次缓存，避免白打数据库。`unlock` 前用 `isHeldByCurrentThread()` 判断，Lua 核对线程标识后才删锁
- [x] 商户类型整份列表缓存在 `cache:shop:type:`，TTL 30 分钟，没有套三件套
- [x] 店铺不存在时 `Result.fail("店铺不存在")`
- [x] 热门笔记用一次 `listByIds` 查用户，不再循环 `getById`
- [x] 退出登录 `POST /user/logout` 仍返回「功能未完成」。不影响秒杀主线，没有要求就别补

Phase 4，商户更新：

- [x] `ShopServiceImpl.update` 加了事务。id 为空直接失败
- [x] 先 `updateById`，再删 `cache:shop:{id}`。删除抛异常时，数据库修改回滚
- [x] 1 秒后由单线程守护线程 `shop-cache-delay-delete` 再删一次。这次删除不挡住接口返回
- [x] 第二次删除不看缓存里现在是什么。可能把刚回填的新数据也删掉，下次查询再查一次库。进程如果在这 1 秒内停掉，第二次删除就没了，剩下的靠 TTL

Phase 5 到 Phase 7，秒杀下单：

- [x] `seckill.lua` 一次做完：券不存在返回 5，未开始 3，已结束 4，库存不足 1，重复下单 2，通过则库存减 1、用户写入 Set 并返回 0。时间用 Redis 的 `TIME`，和券上的 `beginTime` / `endTime`（秒）比较
- [x] 预占是 Hash `seckill:hold:{id}`，field 为用户 id，value 为订单号。订单号在 Lua 之前生成。`seckill:pending:{订单号}` 表示还没入库。旧的 `seckill:order:{id}` Set 只拦住这次改动之前已经抢到的人。取消后可以再抢。库里的旧券没有库存 key 时，Lua 返回 5
- [x] 订单号：`RedisIdWorker`，2022-01-01 UTC 以来的秒数左移 32 位，再或上当天的 `INCR icr:order:yyyy:MM:dd`。`tb_voucher_order.id` 是 `IdType.INPUT`，不是自增
- [x] 限流在秒杀 Lua 之前：`seckill_limit.lua`，key 是 `seckill:limit:{userId}`，1 秒 5 次。超过返回「操作太频繁，请稍后再试」
- [x] 通过后发 `VoucherOrderMessage`（orderId、userId、voucherId）到持久队列 `seckill.order.queue`，走默认交换机，routing key 就是队列名。消息用 JSON
- [x] 发布确认类型是 correlated，`mandatory` 为 true。最多等 3 秒。确认成功且没有被退回，接口才 `Result.ok(orderId)`
- [x] broker 明确拒绝或退回时，执行 `seckill_restore.lua`：用户还在 Set 里才把库存加 1 并移出，调用两次也只加一次
- [x] 消费者手动应答。通过接口调用 `createVoucherOrder`，事务提交之后才 `basicAck`
- [x] 状态：`CREATED` 新订单；`EXISTS` 这个订单号已经在库里，直接应答，不再扣库存；`STOCK_FAILED` 数据库 `stock = stock - 1 AND stock > 0` 没更新到行，先回滚 Redis 再应答；`INVALID` 消息不合法，直接应答。别的异常 `basicNack` 并且重新入队
- [x] 订单插入只写 id、userId、voucherId。状态 1 和 pay_type 1 用的是表的默认值

Phase 8，取消和支付：

- [x] 订单状态：1 未支付，2 已支付，4 已取消
- [x] 入库成功后发 `VoucherOrderCancelMessage`，里面只有 orderId，进等待队列 `seckill.order.delay`。队列参数 `x-message-ttl` 是 900000 毫秒，死信交换机 `seckill.order.dlx`，routing key `seckill.order.cancel`，消费队列 `seckill.order.cancel`
- [x] 配置项 `citygo.seckill.unpaid-timeout` 当前是 `15m`。改这个时间之前，要先删掉已经存在的 `seckill.order.delay`，否则启动会报 PRECONDITION_FAILED
- [x] 取消这条消息如果被 broker 明确拒绝，抛异常，创建订单的事务回滚。确认超过 3 秒只打日志，订单仍然算创建成功
- [x] 取消分成两步。`markOrderCancelled` 只改数据库：未支付改为已取消，并退回 MySQL 库存，然后事务提交。`restoreRedisIfCancelled` 再按订单号回补 Redis。订单号对不上当前预占时，脚本不加库存。消费失败最多 3 次，间隔 5 秒，之后进入 `seckill.order.fail`
- [x] `POST /voucher-order/pay/{id}` 要登录。`status=2` 且写入 `pay_time`，条件是这个订单属于当前用户并且还是未支付。不接第三方支付，也不再扣库存。和取消抢的是同一行 `status=1`

## 故意留着、不要擅自补上的缝

- 发布确认超过 3 秒，或发送结果不确定：接口仍返回订单号，Redis 预占不退。`GET /voucher-order/{id}` 在库里还没有行时返回处理中。连接不上 RabbitMQ 时才会在接口里回补。`SeckillPendingScheduler` 每 5 秒扫描空闲超过 10 秒的 `seckill:pending`：库里已有订单就删掉 key，没有就按原订单号补发。补发失败只打日志，不回补预占。
- 进程停掉以后看门狗也停了，锁最多再留大约 30 秒。不要把这把锁套到秒杀上，秒杀仍用 Lua。
- 页面 `shop-detail.html` 只提示订单号，没有接支付按钮。后端支付接口是好的。没有要求就不要改页面。

## 简历上可以写、以及不能写

可以写：Cache Aside 和三件套、延迟双删、Redisson 只锁商户缓存重建（看门狗和 Lua 解锁）、Redis token 与两个拦截器、Lua 里的时间/库存/一人一单、按用户限流、RabbitMQ 发布确认和手动应答、订单号幂等、扣库存失败回滚 Redis、15 分钟死信取消、支付只改状态、Docker 只跑 RabbitMQ。

缓存旁路和 Lua 秒杀的思路来自黑马点评。简历写「自己实现过，能画出调用链」。CityGo 多做的是：活动时间放进 Lua、用 RabbitMQ 而不是 Redis Stream、确认/应答/幂等/回滚、死信取消、延迟双删、按用户限流。

不能写：Elasticsearch、Caffeine、JWT 双 token、消息零丢失、评论收藏评分、Netty、本地消息表、任何 QPS。Redisson 只写商户缓存这把锁，不要写成秒杀也在用它。


---

# 13. AI 回答风格

开发者提出“这是什么”“为什么”“没看懂”等问题时：

不要只给结论。

按照：

概念
↓
代码
↓
运行过程
↓
小例子
↓
容易混淆的地方

进行解释。

如果涉及 Java 代码，优先逐行解释。

如果涉及调用链，使用：

Controller
↓
Service
↓
Mapper
↓
MySQL

这样的方式展示。

始终优先保证开发者真正理解代码，而不是追求最快完成项目。


---

# 14. 项目最终目标

现在已经做完的，开发者要能独立讲清楚：

- 商户缓存的读和更新，包括空值、Redisson 看门狗、Lua 解锁、过期抖动、延迟双删
- 登录 token 和两个拦截器的顺序
- Lua 秒杀的六个返回值，以及限流为什么放在它前面
- 订单号怎么拼出来
- 消息从确认、入队、手动应答、幂等、失败回滚，到 15 分钟取消的整条链
- 支付和取消为什么都只改 `status = 1` 的那一行
- 发布确认超时为什么不退 Redis 资格

下面这些没有做。可以知道它们解决什么问题，不能说成 CityGo 已经实现：

- Elasticsearch、Feed、GEO
- Caffeine 多级缓存
- JMeter 和性能数字

简历只写第 12 节里「可以写」的那一段。没有实现，或者实现了但讲不清调用链的，都不写。