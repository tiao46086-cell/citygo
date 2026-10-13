-- CityGo 演示数据。按已有编号更新，不改 hmdp.sql。
-- 不改已有店铺的 sold、comments、score。新店这三列写 0。
-- 店铺 1、2、3、5 的 type_id 仍是 1。一隅书屋在书店分类。
-- 经纬度是广州各区附近的示意点，不是真实门店。地址是虚构的。
-- 图片是可自由使用的场景照片，放在页面目录 imgs/demo/，不是这些虚构店的实拍。

-- Docker 初始化每份 SQL 都会新建 mysql 连接，不能依赖基础 SQL 的 SET NAMES。
SET NAMES utf8mb4;
USE hmdp;

-- 去掉基础 SQL 中两篇重复骑马笔记；编号、作者和标题同时匹配才清理，保留用户修改后的笔记。
START TRANSACTION;
DELETE c FROM tb_blog_comments c JOIN tb_blog b ON b.id = c.blog_id
WHERE b.id IN (6, 7) AND b.user_id = 1 AND b.title = '杭州周末好去处｜💰50就可以骑马啦🐎';
DELETE f FROM tb_blog_favorite f JOIN tb_blog b ON b.id = f.blog_id
WHERE b.id IN (6, 7) AND b.user_id = 1 AND b.title = '杭州周末好去处｜💰50就可以骑马啦🐎';
DELETE FROM tb_blog
WHERE id IN (6, 7) AND user_id = 1 AND title = '杭州周末好去处｜💰50就可以骑马啦🐎';
COMMIT;

UPDATE tb_shop
SET name = '巷里咖啡',
    images = '/imgs/demo/shop-1.jpg',
    area = '越秀',
    address = '广州市越秀区榕荫巷18号',
    x = 113.270,
    y = 23.129,
    avg_price = 32,
    open_hours = '08:00-21:00'
WHERE id = 1;

UPDATE tb_shop
SET name = '南风小馆',
    images = '/imgs/demo/shop-2.jpg',
    area = '海珠',
    address = '广州市海珠区南风里6号',
    x = 113.260,
    y = 23.100,
    avg_price = 68,
    open_hours = '11:00-21:30'
WHERE id = 2;

UPDATE tb_shop
SET name = '云间烘焙',
    images = '/imgs/demo/shop-3.jpg',
    area = '天河',
    address = '广州市天河区云间路9号',
    x = 113.330,
    y = 23.140,
    avg_price = 36,
    open_hours = '09:00-20:00'
WHERE id = 3;

UPDATE tb_shop
SET name = '一隅书屋',
    images = '/imgs/demo/shop-4.jpg',
    area = '荔湾',
    address = '广州市荔湾区一隅坊3号',
    x = 113.240,
    y = 23.120,
    avg_price = 42,
    open_hours = '10:00-21:00',
    type_id = 11
WHERE id = 4;

UPDATE tb_shop
SET name = '山野火锅',
    images = '/imgs/demo/shop-5.jpg',
    area = '白云',
    address = '广州市白云区山野街21号',
    x = 113.270,
    y = 23.170,
    avg_price = 98,
    open_hours = '11:00-22:30'
WHERE id = 5;

-- 优惠券 1 仍属于店铺 1，类型保持普通券，金额不变。
UPDATE tb_voucher
SET title = '巷里咖啡50元券',
    sub_title = '演示券，仅供堂食',
    rules = '学习演示，不兑现、不找零，不接入真实支付'
WHERE id = 1
  AND shop_id = 1;

-- 普通券库存列在 sql/citygo-upgrade.sql。这里只给演示券填初始库存，已有库存不覆盖。
UPDATE tb_voucher
SET stock = 100
WHERE id = 1
  AND shop_id = 1
  AND type = 0
  AND stock IS NULL;

-- 笔记 5 挂在店铺 1，改成咖啡馆，不改点赞数和评论数。正文用换行，不再写 HTML。
UPDATE tb_blog
SET title = '巷里咖啡：窗口座位和一杯美式',
    images = '/imgs/demo/blog-5.jpg',
    content = '巷里咖啡在广州市越秀区榕荫巷18号，是演示用的虚构店铺。
靠窗的位子能看到巷口。美式是现磨的，人均大约 32 元。
没有电话，也不接受真实预订。'
WHERE id = 5
  AND shop_id = 1;

-- 笔记 4 挂在店铺 4，改成书店，不再写西餐厅。
-- 评论数改成 0：课程里这列是 104，评论表里没有对应记录。
UPDATE tb_blog
SET title = '一隅书屋：雨天在这里坐一下',
    images = '/imgs/demo/blog-4.jpg',
    content = '一隅书屋在广州市荔湾区一隅坊3号，是演示用的虚构店铺。
靠里的桌子可以看书。饮品和轻食的人均大约 42 元。
没有电话，也不接受真实预订。',
    comments = 0
WHERE id = 4
  AND shop_id = 4;

-- 笔记作者原来叫可可今天不吃肉，头像是课程里的真人照片。改成演示昵称和卡通头像。
UPDATE tb_user
SET nick_name = '巷口的风',
    icon = '/imgs/icons/user-2a.png'
WHERE id = 2;

-- 新秒杀券用 10，不改优惠券 1。库存 20，时间按北京时间。
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
SELECT 10, 5, '山野火锅100元秒杀券', '演示秒杀，模拟支付', '学习演示，不兑现、不找零，不接入真实支付', 1990, 10000, 1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_voucher WHERE id = 10);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
SELECT 10, 20, '2026-01-01 00:00:00', '2027-12-31 23:59:59'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tb_seckill_voucher WHERE voucher_id = 10);

-- 分类：美食、KTV、电影院、健身房，书店排在健身房后面。
DELETE FROM tb_shop_type WHERE id IN (4, 5, 6, 7, 8, 9);

UPDATE tb_shop_type SET name = '电影院' WHERE id = 3;
UPDATE tb_shop_type SET name = '健身房' WHERE id = 10;

INSERT INTO tb_shop_type (id, name, icon, sort)
SELECT 11, '书店', NULL, 5
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop_type WHERE id = 11);

-- 美食里删掉四家课程餐厅。没有笔记和优惠券挂在上面。
DELETE FROM tb_shop WHERE id IN (6, 7, 8, 9);

UPDATE tb_shop
SET name = '晚风练歌',
    images = '/imgs/demo/shop-10.jpg',
    area = '越秀',
    address = '广州市越秀区晚风街8号',
    x = 113.268,
    y = 23.132,
    avg_price = 68,
    open_hours = '12:00-02:00'
WHERE id = 10;

UPDATE tb_shop
SET name = '南音包厢',
    images = '/imgs/demo/shop-11.jpg',
    area = '海珠',
    address = '广州市海珠区南音里3号',
    x = 113.258,
    y = 23.098,
    avg_price = 72,
    open_hours = '12:00-02:00'
WHERE id = 11;

UPDATE tb_shop
SET name = '云上麦社',
    images = '/imgs/demo/shop-12.jpg',
    area = '天河',
    address = '广州市天河区云上路12号',
    x = 113.328,
    y = 23.138,
    avg_price = 80,
    open_hours = '12:00-02:00'
WHERE id = 12;

UPDATE tb_shop
SET name = '巷口麦社',
    images = '/imgs/demo/shop-13.jpg',
    area = '荔湾',
    address = '广州市荔湾区巷口坊5号',
    x = 113.238,
    y = 23.118,
    avg_price = 60,
    open_hours = '12:00-02:00'
WHERE id = 13;

DELETE FROM tb_shop WHERE id = 14;

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 15, '青石影院', 3, '/imgs/demo/shop-15.jpg', '越秀', '广州市越秀区青石巷7号', 113.266, 23.126, 46, 0, 0, 0, '10:00-23:30'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 15);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 16, '江畔影城', 3, '/imgs/demo/shop-16.jpg', '海珠', '广州市海珠区江畔路15号', 113.262, 23.102, 52, 0, 0, 0, '10:00-23:30'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 16);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 17, '云台影院', 3, '/imgs/demo/shop-17.jpg', '天河', '广州市天河区云台路4号', 113.334, 23.142, 48, 0, 0, 0, '10:00-23:30'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 17);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 18, '晨练健身', 10, '/imgs/demo/shop-18.jpg', '荔湾', '广州市荔湾区晨练路11号', 113.236, 23.122, 89, 0, 0, 0, '07:00-22:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 18);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 19, '平地健身', 10, '/imgs/demo/shop-19.jpg', '白云', '广州市白云区平地街6号', 113.274, 23.172, 79, 0, 0, 0, '07:00-22:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 19);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 20, '榕下健身', 10, '/imgs/demo/shop-20.jpg', '越秀', '广州市越秀区榕下街2号', 113.264, 23.134, 99, 0, 0, 0, '07:00-22:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 20);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 21, '南窗书店', 11, '/imgs/demo/shop-21.jpg', '海珠', '广州市海珠区南窗里9号', 113.256, 23.104, 38, 0, 0, 0, '10:00-21:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 21);

INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
SELECT 22, '云页书屋', 11, '/imgs/demo/shop-22b.jpg', '天河', '广州市天河区云页路6号', 113.332, 23.144, 45, 0, 0, 0, '10:00-21:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM tb_shop WHERE id = 22);

UPDATE tb_shop SET images = '/imgs/demo/shop-22b.jpg' WHERE id = 22;

-- 分类列表是一整份 Redis 字符串，删行后要删掉，否则 30 分钟内还是旧列表。
-- DEL cache:shop:type:

-- 灌进正在运行的库之后，店铺详情还会命中旧缓存。
-- Caffeine 最多 60 秒；Redis 要删掉这些 key：
-- DEL cache:shop:1 cache:shop:2 cache:shop:3 cache:shop:4 cache:shop:5
-- 秒杀按钮读的是 MySQL，下单还要 Redis。缺库存 key 时脚本返回 5。
-- SET seckill:stock:10 20
-- HSET seckill:voucher:10 beginTime 1767196800 endTime 1830268799
