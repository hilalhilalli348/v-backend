package com.videostreaming.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        // Tüm origin'lere izin ver (production'da belirli domain'lere kısıtla)
        corsConfig.addAllowedOrigin("*"); // Credentials ile birlikte kullanılamaz
        // veya belirli originlere izin ver:
        // corsConfig.addAllowedOrigin("http://127.0.0.1:8001");
        // corsConfig.addAllowedOrigin("http://localhost:8001");

        // İzin verilen HTTP metodları
        corsConfig.addAllowedMethod("*");

        // İzin verilen header'lar
        corsConfig.addAllowedHeader("*");

        // Credentials kullanmayı kapat (wildcard origin ile çalışması için)
        corsConfig.setAllowCredentials(false);

        // Preflight cache süresi
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}