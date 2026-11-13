package top.goldgom.aibotassist.client;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Public native-app registration; no client secret is distributed with the mod. */
public final class TokenNestOAuth {
    public static final String ISSUER = "https://openai.goldgom.top";
    public static final String API_BASE = ISSUER + "/v1";
    public static final String DEFAULT_CLIENT_ID = "tnc_eqezq1pCPAZ4oOkU90ZEchhiyvFf3Pks";
    public static final String CALLBACK_PATH = "/aibotassist/callback";
    public static final String REGISTERED_REDIRECT_URI = "http://127.0.0.1" + CALLBACK_PATH;
    public static final String SCOPES = "api offline_access";
    private TokenNestOAuth() {}

    public static void requireApi(String base) {
        if (!API_BASE.equals(AiConnection.endpoint(base).toString()))
            throw new IllegalArgumentException("TokenNest 登录需使用 " + API_BASE);
    }
    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static URI authorization(String clientId, int port, String state, String verifier) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("请填写 TokenNest 后台创建的 client_id");
        return URI.create(ISSUER + "/oauth/authorize?response_type=code&client_id=" + enc(clientId)
            + "&redirect_uri=" + enc(redirect(port)) + "&scope=" + enc(SCOPES) + "&state=" + enc(state)
            + "&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256");
    }
    public static String redirect(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("回调端口无效");
        return "http://127.0.0.1:" + port + CALLBACK_PATH;
    }
    public static Map<String, String> callbackQuery(String raw, String expectedState) {
        if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("Invalid callback");
        Map<String, String> query = new HashMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) throw new IllegalArgumentException("Invalid callback");
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            if (query.putIfAbsent(key, URLDecoder.decode(kv[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Duplicate callback parameter");
        }
        if (expectedState == null || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8),
                query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid state");
        boolean code = query.containsKey("code") && !query.get("code").isBlank();
        boolean error = query.containsKey("error") && !query.get("error").isBlank();
        if (code == error || (query.containsKey("code") && query.containsKey("error"))) throw new IllegalArgumentException("Expected code or error");
        return query;
    }
    public static String registrationInfo(String clientId) {
        return "name: AI Bot Assist\nclient_id: " + clientId + "\nredirect_uri: " + REGISTERED_REDIRECT_URI
            + "\nscopes: " + SCOPES + "\nclient_type: public\ngrant_types: authorization_code refresh_token"
            + "\nresponse_type: code\nPKCE: S256\ntoken_endpoint_auth_method: none"
            + "\nauthorization_endpoint: " + ISSUER + "/oauth/authorize"
            + "\ntoken_endpoint: " + ISSUER + "/api/oauth2/token";
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
