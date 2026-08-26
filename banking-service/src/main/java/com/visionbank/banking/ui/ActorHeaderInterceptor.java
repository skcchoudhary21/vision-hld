package com.visionbank.banking.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

// Every request the SPA makes must identify who's asking. Enforced at one
// choke point rather than per-controller-method so no new endpoint can
// accidentally forget it. CORS preflight (OPTIONS) is exempt -- the browser's
// own negotiation request, sent before it's allowed to attach custom headers.
public class ActorHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String actorId = request.getHeader("X-Actor-Id");
        String actorRole = request.getHeader("X-Actor-Role");
        if (isBlank(actorId) || isBlank(actorRole)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"MISSING_ACTOR_HEADERS\"}");
            return false;
        }
        return true;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
