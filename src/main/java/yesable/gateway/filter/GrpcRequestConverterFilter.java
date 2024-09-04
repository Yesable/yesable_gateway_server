package yesable.gateway.filter;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import yesable.gateway.service.filter.*;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcRequestConverterFilter implements GatewayFilter, Ordered {

    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        Map<String, String> queryParams = exchange.getRequest().getQueryParams().toSingleValueMap();

        return Mono.defer(() -> chooseInstance("AUTH-SERVICE")
                .flatMap(instance -> {
                    if (instance == null) {
                        log.error("No available instances for AUTH-SERVICE");
                        return chain.filter(exchange);
                    }

                    Map<String, String> metadata = instance.getMetadata();
                    String grpcPort = metadata.get("gRPC_port");

                    if (grpcPort == null) {
                        log.error("No gRPC port found in service instance metadata");
                        return chain.filter(exchange);
                    }

                    ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(grpcPort))
                            .usePlaintext()
                            .build();

                    AuthServiceGrpc.AuthServiceStub stub = AuthServiceGrpc.newStub(channel);

                    return Mono.create(sink -> {
                        if (path.contains("/auth-service/create-auth")) {
                            AuthData authData = AuthData.newBuilder()
                                    .setId(queryParams.get("id"))
                                    .setPw(queryParams.get("pw"))
                                    .build();
                            CreateTokenRequest request = CreateTokenRequest.newBuilder()
                                    .setAuth(authData)
                                    .build();

                            StreamObserver<CreateTokenResponse> responseObserver = new StreamObserver<>() {
                                @Override
                                public void onNext(CreateTokenResponse response) {
                                    sink.success(response.toByteArray());
                                }

                                @Override
                                public void onError(Throwable t) {
                                    sink.error(t);
                                }

                                @Override
                                public void onCompleted() {
                                    // no-op
                                }
                            };

                            stub.createAuth(request, responseObserver);

                        } else if (path.contains("/auth-service/verify-auth")) {
                            VerifyTokenRequest request = VerifyTokenRequest.newBuilder()
                                    .setToken(queryParams.get("token"))
                                    .build();

                            StreamObserver<VerifyTokenResponse> responseObserver = new StreamObserver<>() {
                                @Override
                                public void onNext(VerifyTokenResponse response) {
                                    sink.success(response.toByteArray());
                                }

                                @Override
                                public void onError(Throwable t) {
                                    sink.error(t);
                                }

                                @Override
                                public void onCompleted() {
                                    // no-op
                                }
                            };

                            stub.verifyAuth(request, responseObserver);

                        } else {
                            sink.success(new byte[0]);
                        }
                    }).flatMap(responseBytes -> {
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        return exchange.getResponse().writeWith(
                                Mono.just(exchange.getResponse().bufferFactory().wrap((byte[]) responseBytes))
                        );
                    }).doFinally(signalType -> channel.shutdown());
                })
                .switchIfEmpty(chain.filter(exchange))
        ).onErrorResume(e -> {
            log.error("Exception occurred during gRPC call", e);
            return chain.filter(exchange);
        });
    }

    private Mono<ServiceInstance> chooseInstance(String serviceId) {
        ReactorServiceInstanceLoadBalancer loadBalancer = (ReactorServiceInstanceLoadBalancer) loadBalancerFactory.getInstance(serviceId);
        return Mono.defer(() -> loadBalancer.choose()
                .map(response -> response.hasServer() ? response.getServer() : null));
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
