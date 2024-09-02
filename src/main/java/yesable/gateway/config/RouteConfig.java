package yesable.gateway.config;

import lombok.AllArgsConstructor;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import yesable.gateway.filter.GrpcLoadBalancerFilter;

@Configuration
@AllArgsConstructor
public class RouteConfig {

    private final GrpcLoadBalancerFilter grpcLoadBalancerFilter;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth-service", r -> r.path("/auth-service/**")
                        .filters(f -> f.filter(grpcLoadBalancerFilter.apply(new Object())))
                        .uri("lb://AUTH-SERVICE"))
                .route("member-service", r -> r.path("/member-service/**")
                        .filters(f -> f.filter(grpcLoadBalancerFilter.apply(new Object())))
                        .uri("lb://MEMBER-SERVICE"))
                .build();
    }
}
