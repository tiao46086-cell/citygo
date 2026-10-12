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
