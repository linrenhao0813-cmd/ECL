package com.ecl.auth;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Map;

/** Injectable HTTP boundary for authentication flows. */
interface AuthHttpTransport {
    HttpUtil.Response postForm(String url, Map<String, String> form) throws IOException;

    HttpUtil.Response postJson(String url, JsonObject body) throws IOException;

    HttpUtil.Response getBearer(String url, String bearerToken) throws IOException;

    static AuthHttpTransport system() {
        return new AuthHttpTransport() {
            @Override
            public HttpUtil.Response postForm(String url, Map<String, String> form)
                    throws IOException {
                return HttpUtil.postForm(url, form);
            }

            @Override
            public HttpUtil.Response postJson(String url, JsonObject body) throws IOException {
                return HttpUtil.postJsonResponse(url, body);
            }

            @Override
            public HttpUtil.Response getBearer(String url, String bearerToken) throws IOException {
                return HttpUtil.getBearer(url, bearerToken);
            }
        };
    }
}
