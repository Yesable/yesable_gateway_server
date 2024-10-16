package yesable.gateway.config;

import lombok.AllArgsConstructor;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yesable.gateway.filter.GrpcAuthRequestConverterFilter;
import yesable.gateway.filter.GrpcResumeRequestConverterFilter;

@Configuration
@AllArgsConstructor
public class RouteConfig {

    private final GrpcAuthRequestConverterFilter grpcAuthRequestConverterFilter;
    private final GrpcResumeRequestConverterFilter grpcResumeRequestConverterFilter;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth-service", r -> r.path("/auth/**")
                        .filters(f -> {
                            f.filter(grpcAuthRequestConverterFilter);
                            return f;
                        })
                        .uri("lb://AUTH-SERVICE"))
                .route("member-service", r -> r.path("/member/**")
                        .filters(f -> {
                            f.filter(grpcAuthRequestConverterFilter);
                            return f;
                        })
                        .uri("lb://MEMBER-SERVICE"))
                .route("resume-service", r -> r.path("/resume/**")
                        .filters(f -> {
                            f.filter(grpcResumeRequestConverterFilter);
                            return f;
                        })
                        .uri("lb://RESUME-SERVICE"))
                .build();
    }
}
