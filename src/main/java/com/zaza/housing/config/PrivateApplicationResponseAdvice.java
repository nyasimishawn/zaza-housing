package com.zaza.housing.config;

import com.zaza.housing.controller.ApplicationReviewController;
import com.zaza.housing.controller.NotificationController;
import com.zaza.housing.controller.TenantApplicationController;
import com.zaza.housing.controller.TenantProfileController;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(assignableTypes = {TenantProfileController.class, TenantApplicationController.class,
        ApplicationReviewController.class, NotificationController.class})
public class PrivateApplicationResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return body;
    }
}
