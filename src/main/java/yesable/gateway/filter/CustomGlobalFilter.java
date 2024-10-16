package yesable.gateway.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yesable.gateway.client.AuthServiceClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private final AuthServiceClient authServiceClient;  // 토큰 검증을 위한 공통 모듈

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // auth-service 경로가 아닌 경우에만 토큰 검증을 실행
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/auth")) {
            return chain.filter(exchange);  // auth-service로의 요청은 검증하지 않음
        }

        // 요청에서 Authorization 헤더로부터 토큰 추출
        String token = extractTokenFromRequest(exchange);

        if (token == null || token.isEmpty()) {
            return chain.filter(exchange);  // 토큰이 없으면 다음 필터로 넘어감
        }

        // AuthServiceClient를 사용해 gRPC로 토큰 검증 요청
        return authServiceClient.verifyToken(token)
                .flatMap(userId -> {
                    // userId를 ServerWebExchange의 attributes에 저장
                    exchange.getAttributes().put("userId", userId);

                    ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(exchange.getRequest())
                            .response(exchange.getResponse())
                            .build();

                    // 다음 필터로 진행
                    return chain.filter(mutatedExchange);
                })
                .switchIfEmpty(chain.filter(exchange))  // 토큰 검증 실패 시 다음 필터로 넘어감
                .onErrorResume(e -> {
                    log.error("Error during token verification", e);
                    return chain.filter(exchange);  // 오류 발생 시에도 다음 필터로 넘어감
                });
    }

    private String extractTokenFromRequest(ServerWebExchange exchange) {
        // Authorization 헤더에서 Bearer 토큰 추출
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);  // "Bearer " 부분을 제거한 토큰 반환
        }
        return null;
    }

    @Override
    public int getOrder() {
        return -3;
    }
}
