package com.trading.central.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AccountService {

    private final RestTemplate restTemplate;

    @Value("${app.account.api-base}")
    private String apiBase;

    @Value("${app.account.mock:true}")
    private boolean isMock;

    public AccountService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private void callAccountApi(String path, Map<String, Object> body) {
        if (isMock) {
            log.debug("[AccountService Mock] {} {}", path, body);
            return;
        }

        String url = apiBase + path;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("[AccountService] {} failed: {}", path, response.getStatusCode());
                throw new RuntimeException("Account API error: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[AccountService] {} call error: {}", path, e.getMessage());
            throw new RuntimeException("Account API call failed", e);
        }
    }

    public void freezeFunds(String accountId, BigDecimal amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("fund_acc_no", accountId);
        body.put("delta_fund_a", amount.negate());
        body.put("delta_fund_f", amount);
        callAccountApi("/api/fund-accounts/updateBalance", body);
    }

    public void settleBuyFunds(String accountId, BigDecimal amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("fund_acc_no", accountId);
        body.put("delta_fund_a", BigDecimal.ZERO);
        body.put("delta_fund_f", amount.negate());
        callAccountApi("/api/fund-accounts/updateBalance", body);
    }

    public void settleSellFunds(String accountId, BigDecimal amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("fund_acc_no", accountId);
        body.put("delta_fund_a", amount);
        body.put("delta_fund_f", BigDecimal.ZERO);
        callAccountApi("/api/fund-accounts/updateBalance", body);
    }

    public void releaseFunds(String accountId, BigDecimal amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("fund_acc_no", accountId);
        body.put("delta_fund_a", amount);
        body.put("delta_fund_f", amount.negate());
        callAccountApi("/api/fund-accounts/updateBalance", body);
    }

    public void freezeHolding(String accountId, String stockCode, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("sec_acc_no", accountId);
        body.put("stock_code", stockCode);
        body.put("delta_security_a", -quantity);
        body.put("delta_security_f", quantity);
        callAccountApi("/api/security-accounts/updateHolding", body);
    }

    public void settleSellerHolding(String accountId, String stockCode, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("sec_acc_no", accountId);
        body.put("stock_code", stockCode);
        body.put("delta_security_a", 0);
        body.put("delta_security_f", -quantity);
        callAccountApi("/api/security-accounts/updateHolding", body);
    }

    public void settleBuyerHolding(String accountId, String stockCode, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("sec_acc_no", accountId);
        body.put("stock_code", stockCode);
        body.put("delta_security_a", quantity);
        body.put("delta_security_f", 0);
        callAccountApi("/api/security-accounts/updateHolding", body);
    }

    public void releaseHolding(String accountId, String stockCode, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("sec_acc_no", accountId);
        body.put("stock_code", stockCode);
        body.put("delta_security_a", quantity);
        body.put("delta_security_f", -quantity);
        callAccountApi("/api/security-accounts/updateHolding", body);
    }

    public String getAccountName(String accountId) {
        if (isMock) {
            String suffix = accountId.length() >= 4 ? accountId.substring(accountId.length() - 4) : accountId;
            return "user" + suffix;
        }

        String url = apiBase + "/api/fund-accounts/" + accountId + "/name";
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object name = response.getBody().get("accountName");
                if (name != null) {
                    return name.toString();
                }
                Object realName = response.getBody().get("realName");
                if (realName != null) {
                    return realName.toString();
                }
            }
        } catch (Exception e) {
            log.warn("[AccountService] get account name failed: {}, using default", accountId, e);
        }
        return "user" + (accountId.length() >= 4 ? accountId.substring(accountId.length() - 4) : accountId);
    }
}
