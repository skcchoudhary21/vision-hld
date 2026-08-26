package com.visionbank.banking.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Dev-tool support: the approval-console-ui React SPA runs on its own Vite dev
// server (a different origin from banking-service:8080), unlike the old
// same-origin static ui.html. Scoped to /ui-api/** and /transfers/** -- the
// only endpoints a browser-based SPA calls -- not a blanket allow-all.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    public CorsConfig(@Value("${ui.allowed-origin:http://localhost:5173}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/ui-api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("*");
        registry.addMapping("/transfers/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ActorHeaderInterceptor())
                .addPathPatterns("/ui-api/**", "/transfers/**");
    }
}
