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
import reactor.core.publisher.MonoSink;
import yesable.gateway.constant.ConverterConstants;
import yesable.gateway.service.filter.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcRequestConverterFilter implements GatewayFilter, Ordered {

    private final ConverterConstants converterConstants;
    private final Map<String, ManagedChannel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, AuthServiceGrpc.AuthServiceStub> stubMap = new ConcurrentHashMap<>();

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
        if (path.contains("/auth-service/create-auth")) {
            AuthData authData = AuthData.newBuilder()
                    .setId(queryParams.get("id"))
                    .setPw(queryParams.get("pw"))
                    .build();
            CreateTokenRequest request = CreateTokenRequest.newBuilder()
                    .setAuth(authData)
                    .build();

            return callCreateAuth(request, stub);

        } else if (path.contains("/auth-service/verify-auth")) {
            VerifyTokenRequest request = VerifyTokenRequest.newBuilder()
                    .setToken(queryParams.get("token"))
                    .build();

            return callVerifyAuth(request, stub);

        } else {
            return Mono.just(new byte[0]);
        }
    }

    private Mono<byte[]> callCreateAuth(CreateTokenRequest request, AuthServiceGrpc.AuthServiceStub stub) {
        return Mono.create(sink -> stub.createAuth(request, new SimpleStreamObserver<>(sink)));
    }

    private Mono<byte[]> callVerifyAuth(VerifyTokenRequest request, AuthServiceGrpc.AuthServiceStub stub) {
        return Mono.create(sink -> stub.verifyAuth(request, new SimpleStreamObserver<>(sink)));
    }

    @Override
    public int getOrder() {
        return -2;
    }

    private record SimpleStreamObserver<T extends Message>(MonoSink<byte[]> sink) implements StreamObserver<T> {

        @Override
            public void onNext(T response) {
                try {
                    String jsonResponse = JsonFormat.printer().print(response);
                    sink.success(jsonResponse.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    sink.error(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                sink.error(t);
            }

            @Override
            public void onCompleted() {
            }
        }
}
