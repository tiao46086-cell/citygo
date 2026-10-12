-- 结构升级。不写演示商户、笔记或库存数量，可以单独在已有库上执行。
-- 普通券库存放在 tb_voucher.stock。秒杀券库存仍在 tb_seckill_voucher，这一列保持空。
USE hmdp;

SET @citygo_voucher_stock := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tb_voucher'
    AND COLUMN_NAME = 'stock'
);
SET @citygo_voucher_stock_sql := IF(
  @citygo_voucher_stock = 0,
  'ALTER TABLE tb_voucher ADD COLUMN stock int(8) NULL DEFAULT NULL COMMENT ''普通券库存。秒杀券不使用这一列'' AFTER status',
  'SELECT 1'
);
PREPARE citygo_stmt FROM @citygo_voucher_stock_sql;
EXECUTE citygo_stmt;
DEALLOCATE PREPARE citygo_stmt;

-- 体验账号回收后隐藏旧订单，但不删除订单行。秒杀消息重投仍能靠主键认出这一单。
SET @citygo_demo_order_after := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tb_user'
    AND COLUMN_NAME = 'demo_order_after'
);
SET @citygo_demo_order_after_sql := IF(
  @citygo_demo_order_after = 0,
  'ALTER TABLE tb_user ADD COLUMN demo_order_after bigint(20) NULL DEFAULT NULL COMMENT ''体验账号隐藏该订单号及更早的订单'' AFTER update_time',
  'SELECT 1'
);
PREPARE citygo_stmt FROM @citygo_demo_order_after_sql;
EXECUTE citygo_stmt;
DEALLOCATE PREPARE citygo_stmt;
