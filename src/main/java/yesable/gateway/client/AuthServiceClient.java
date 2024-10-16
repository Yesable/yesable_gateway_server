package yesable.gateway.client;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import yesable.auth.module.service.AuthServiceGrpc;
import yesable.auth.module.service.VerifyTokenRequest;
import yesable.auth.module.service.VerifyTokenResponse;
import yesable.gateway.constant.ConverterConstants;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@AllArgsConstructor
public class AuthServiceClient {

    private final ConverterConstants converterConstants;
    private final Map<String, ManagedChannel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, AuthServiceGrpc.AuthServiceStub> stubMap = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        channelMap.values().forEach(channel -> {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdownNow();
            }
        });
    }

    // gRPC 채널 설정 및 AuthServiceStub 생성 (Mono로 반환하여 비동기 처리)
    private Mono<AuthServiceGrpc.AuthServiceStub> getAuthServiceStub() {
        return converterConstants.chooseInstance("AUTH-SERVICE")
                .map(instance -> {
                    String instanceKey = instance.getHost() + ":" + instance.getPort();
                    return stubMap.computeIfAbsent(instanceKey, key -> {
                        ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("gRPC_port")))
                                .usePlaintext()
                                .build();
                        channelMap.put(instanceKey, channel);
                        return AuthServiceGrpc.newStub(channel);
                    });
                });
    }

    // 비동기적으로 토큰 검증하는 메서드
    public Mono<String> verifyToken(String token) {
        return getAuthServiceStub().flatMap(stub -> Mono.create(sink -> {
            VerifyTokenRequest request = VerifyTokenRequest.newBuilder()
                    .setToken(token)
                    .build();
            // 비동기적으로 gRPC 요청을 처리하고 결과를 Mono로 반환
            SimpleStreamObserver<VerifyTokenResponse> observer = new SimpleStreamObserver<>(sink);
            stub.verifyAuth(request, observer);
        }));
    }

    // gRPC 응답 처리용 observer (MonoSink를 사용하여 비동기 처리)
    private static class SimpleStreamObserver<T extends Message> implements StreamObserver<T> {

        private final MonoSink<String> sink;

        public SimpleStreamObserver(MonoSink<String> sink) {
            this.sink = sink;
        }

        @Override
        public void onNext(T response) {
            try {
                // VerifyTokenResponse에서 userId를 추출하여 MonoSink에 전달
                VerifyTokenResponse verifyTokenResponse = (VerifyTokenResponse) response;
                String userId = verifyTokenResponse.getV().getAuth().getUserId();
                sink.success(userId);  // 성공적으로 응답을 받으면 userId를 Mono에 전달
            } catch (Exception e) {
                sink.error(e);  // 예외가 발생하면 Mono에 에러 전달
            }
        }

        @Override
        public void onError(Throwable t) {
            sink.error(t);  // 에러 발생 시 Mono에 에러 전달
        }

        @Override
        public void onCompleted() {
            // 완료 시 처리할 추가 작업이 있을 경우 여기에 작성 가능
        }
    }
}
