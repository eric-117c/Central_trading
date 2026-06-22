$buyers = @("BUYER_001","BUYER_002","BUYER_003","BUYER_004","BUYER_005","BUYER_006","BUYER_007","BUYER_008","BUYER_009","BUYER_010")
$sellers = @("SELLER_001","SELLER_002","SELLER_003","SELLER_004","SELLER_005","SELLER_006","SELLER_007","SELLER_008","SELLER_009","SELLER_010")

$base = "http://localhost:8082/api/central-trading/orders"
$prefix = (Get-Date -Format "yyyyMMddHHmmss")

Write-Host "=== 撮合测试开始 ===" -ForegroundColor Cyan
Write-Host ""

# 10 笔买单
for ($i = 0; $i -lt 10; $i++) {
    $body = "{`"accountId`":`"$($buyers[$i])`",`"orderId`":`"$($prefix)_BUY_$($i.ToString('D2'))`",`"stockCode`":`"000001`",`"side`":`"BUY`",`"price`":11.50,`"quantity`":100}"
    try {
        $r = Invoke-RestMethod -Uri $base -Method POST -Body $body -ContentType "application/json"
        Write-Host "[BUY  $i] $($r.data.orderNo) - $($r.data.status)" -ForegroundColor Blue
    } catch {
        Write-Host "[BUY  $i] 失败: $_" -ForegroundColor Red
    }
}

Write-Host ""

# 10 笔卖单
for ($i = 0; $i -lt 10; $i++) {
    $body = "{`"accountId`":`"$($sellers[$i])`",`"orderId`":`"$($prefix)_SELL_$($i.ToString('D2'))`",`"stockCode`":`"000001`",`"side`":`"SELL`",`"price`":11.50,`"quantity`":100}"
    try {
        $r = Invoke-RestMethod -Uri $base -Method POST -Body $body -ContentType "application/json"
        Write-Host "[SELL $i] $($r.data.orderNo) - $($r.data.status)" -ForegroundColor Green
    } catch {
        Write-Host "[SELL $i] 失败: $_" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "=== 测试完成，查看 http://localhost:8082 ===" -ForegroundColor Cyan
