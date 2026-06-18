package com.fintrack.common.config;

import com.fintrack.audit.interceptor.ActivityAuditInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // Optional — absent in @WebMvcTest slices where MongoDB is not configured
    @Nullable
    @Autowired(required = false)
    private ActivityAuditInterceptor activityAuditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (activityAuditInterceptor != null) {
            registry.addInterceptor(activityAuditInterceptor)
                    .addPathPatterns("/api/**");
        }
    }
}
