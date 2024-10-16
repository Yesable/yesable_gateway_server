package yesable.gateway.filter;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yesable.auth.module.service.*;
import yesable.gateway.constant.ConverterConstants;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAuthRequestConverterFilter implements GatewayFilter, Ordered {

    private final ConverterConstants converterConstants;
    private final Map<String, ManagedChannel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, AuthServiceGrpc.AuthServiceStub> stubMap = new ConcurrentHashMap<>();

    // JSON 변환기 캐싱
    private static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer();

    @PreDestroy
    public void shutdown() {
        channelMap.values().forEach(channel -> {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
        });
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        Map<String, String> queryParams = exchange.getRequest().getQueryParams().toSingleValueMap();

        return converterConstants.chooseInstance("AUTH-SERVICE")
                .flatMap(instance -> {
                    if (instance == null) {
                        log.error("No available instances for AUTH-SERVICE");
                        return chain.filter(exchange);
                    }

                    String instanceKey = instance.getHost() + ":" + instance.getPort();
                    AuthServiceGrpc.AuthServiceStub stub = stubMap.computeIfAbsent(instanceKey, key -> {
                        ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("gRPC_port")))
                                .usePlaintext()
                                .build();
                        channelMap.put(instanceKey, channel);
                        return AuthServiceGrpc.newStub(channel);
                    });

                    return makeGrpcCall(path, queryParams, stub)
                            .flatMap(responseBytes -> {
                                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                return exchange.getResponse().writeWith(
                                        Mono.just(exchange.getResponse().bufferFactory().wrap(responseBytes))
                                );
                            });
                })
                .switchIfEmpty(chain.filter(exchange))
                .onErrorResume(e -> {
                    log.error("Exception occurred during gRPC call", e);
                    return chain.filter(exchange);
                });
    }

    private Mono<byte[]> makeGrpcCall(String path, Map<String, String> queryParams, AuthServiceGrpc.AuthServiceStub stub) {
        return Mono.fromCallable(() -> {
            if (path.contains("/auth/create")) {
                Login login = Login.newBuilder()
                        .setAccount(AccountData.newBuilder()
                                .setId(queryParams.get("id"))
                                .setPw(queryParams.get("pw"))
                                .build())
                        .build();
                CreateTokenRequest request = CreateTokenRequest.newBuilder()
                        .setLogin(login)
                        .build();

                return callCreateAuth(request, stub).block(); // 비동기 블록 대체

            }
            return new byte[0];
        });
    }

    private Mono<byte[]> callCreateAuth(CreateTokenRequest request, AuthServiceGrpc.AuthServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<CreateTokenResponse> observer = new SimpleStreamObserver<>();
            stub.createAuth(request, observer);
            return observer.getResponse(); // 응답 데이터 바로 반환
        });
    }

    @Override
    public int getOrder() {
        return -2;
    }

    private static class SimpleStreamObserver<T extends Message> implements StreamObserver<T> {

        private final CompletableFuture<byte[]> future = new CompletableFuture<>();

        public byte[] getResponse() {
            try {
                return future.get(); // 결과값을 동기식으로 반환
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onNext(T response) {
            try {
                String jsonResponse = JSON_PRINTER.print(response);
                future.complete(jsonResponse.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }

        @Override
        public void onError(Throwable t) {
            future.completeExceptionally(t);
        }

        @Override
        public void onCompleted() {
        }
    }
}
