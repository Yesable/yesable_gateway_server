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
import yesable.gateway.constant.ConverterConstants;
import yesable.recruit.module.service.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcRecruitRequestConverterFilter implements GatewayFilter, Ordered {

    private final ConverterConstants converterConstants;
    private final Map<String, ManagedChannel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, RecruitServiceGrpc.RecruitServiceStub> stubMap = new ConcurrentHashMap<>();
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

        return converterConstants.chooseInstance("RECRUIT-SERVICE")
                .flatMap(instance -> {
                    if (instance == null) {
                        log.error("No available instances for RECRUIT-SERVICE");
                        return chain.filter(exchange);
                    }

                    String instanceKey = instance.getHost() + ":" + instance.getPort();
                    RecruitServiceGrpc.RecruitServiceStub stub = stubMap.computeIfAbsent(instanceKey, key -> {
                        ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("gRPC_port")))
                                .usePlaintext()
                                .build();
                        channelMap.put(instanceKey, channel);
                        return RecruitServiceGrpc.newStub(channel);
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

    private Mono<byte[]> makeGrpcCall(String path, Map<String, String> queryParams, RecruitServiceGrpc.RecruitServiceStub stub) {
        return Mono.fromCallable(() -> {
            if (path.contains("/recruit/detail")) {
                GetRecruitRequest request = GetRecruitRequest.newBuilder()
                        .setRecruitId(queryParams.get("recruitId"))
                        .build();

                return callGetRecruitDetail(request, stub).block();

            } else if (path.contains("/recruit/form")) {
                GetResumeFormRequest request = GetResumeFormRequest.newBuilder()
                        .setRecruitId(queryParams.get("recruitId"))
                        .build();

                return callGetResumeForm(request, stub).block();
            }
            return new byte[0];
        });
    }

    private Mono<byte[]> callGetRecruitDetail(GetRecruitRequest request, RecruitServiceGrpc.RecruitServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<RecruitResponse> observer = new SimpleStreamObserver<>();
            stub.getRecruitDetail(request, observer);
            return observer.getResponse();
        });
    }

    private Mono<byte[]> callGetResumeForm(GetResumeFormRequest request, RecruitServiceGrpc.RecruitServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<ResumeFormResponse> observer = new SimpleStreamObserver<>();
            stub.getResumeForm(request, observer);
            return observer.getResponse();
        });
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private static class SimpleStreamObserver<T extends Message> implements StreamObserver<T> {

        private final CompletableFuture<byte[]> future = new CompletableFuture<>();

        public byte[] getResponse() {
            try {
                return future.get();
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
