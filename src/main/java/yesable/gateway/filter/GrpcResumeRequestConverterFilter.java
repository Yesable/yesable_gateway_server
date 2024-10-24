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
import yesable.resume.module.service.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcResumeRequestConverterFilter implements GatewayFilter, Ordered {

    private final ConverterConstants converterConstants;
    private final Map<String, ManagedChannel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, OnboardingServiceGrpc.OnboardingServiceStub> onboardingStubMap = new ConcurrentHashMap<>();
    private final Map<String, ResumeServiceGrpc.ResumeServiceStub> resumeStubMap = new ConcurrentHashMap<>();


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
        String userId = exchange.getAttribute("userId");

        return converterConstants.chooseInstance("RESUME-SERVICE")
                .flatMap(instance -> {
                    if (instance == null) {
                        log.error("No available instances for RESUME-SERVICE");
                        return chain.filter(exchange);
                    }

                    String instanceKey = instance.getHost() + ":" + instance.getPort();
                    if (path.contains("/resume/onboarding")) {
                        OnboardingServiceGrpc.OnboardingServiceStub onboardingStub = onboardingStubMap.computeIfAbsent(instanceKey, key -> {
                            ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("gRPC_port")))
                                    .usePlaintext()
                                    .build();
                            channelMap.put(instanceKey, channel);
                            return OnboardingServiceGrpc.newStub(channel);
                        });

                        return makeGrpcCallForOnboarding(userId, path, queryParams, onboardingStub)
                                .flatMap(responseBytes -> {
                                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                    return exchange.getResponse().writeWith(
                                            Mono.just(exchange.getResponse().bufferFactory().wrap(responseBytes))
                                    );
                                });

                    } else {
                        ResumeServiceGrpc.ResumeServiceStub resumeStub = resumeStubMap.computeIfAbsent(instanceKey, key -> {
                            ManagedChannel channel = ManagedChannelBuilder.forAddress(instance.getHost(), Integer.parseInt(instance.getMetadata().get("gRPC_port")))
                                    .usePlaintext()
                                    .build();
                            channelMap.put(instanceKey, channel);
                            return ResumeServiceGrpc.newStub(channel);
                        });

                        return makeGrpcCallForResume(userId, path, queryParams, resumeStub)
                                .flatMap(responseBytes -> {
                                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                    return exchange.getResponse().writeWith(
                                            Mono.just(exchange.getResponse().bufferFactory().wrap(responseBytes))
                                    );
                                });
                    }
                })
                .switchIfEmpty(chain.filter(exchange))
                .onErrorResume(e -> {
                    log.error("Exception occurred during gRPC call", e);
                    return chain.filter(exchange);
                });
    }


    private Mono<byte[]> makeGrpcCallForOnboarding(String userId, String path, Map<String, String> queryParams, OnboardingServiceGrpc.OnboardingServiceStub stub) {

        return Mono.fromCallable(() -> {
            if (path.contains("/resume/onboarding")) {
                // DisabilityType 설정
                DisabilityType disabilityType = DisabilityType.valueOf(queryParams.get("disabilityType").toUpperCase());

                // LocationType 설정
                LocationType locationType = LocationType.valueOf(queryParams.get("locationType").toUpperCase());

                // isTransit 설정
                boolean isTransit = Boolean.parseBoolean(queryParams.get("isTransit"));

                // EmploymentHistoryData 리스트 설정
                List<EmploymentHistoryData> employmentHistoryDataList = Arrays.stream(queryParams.get("employmentHistory").split(","))
                        .map(employmentStr -> {
                            String[] fields = employmentStr.split(";");
                            return EmploymentHistoryData.newBuilder()
                                    .setCorporateName(fields[0])
                                    .setStartDate(fields[1])
                                    .setEndDate(fields[2])
                                    .setIsEmployed(Boolean.parseBoolean(fields[3]))
                                    .setDuty(fields[4])
                                    .setJobDescription(fields[5])
                                    .build();
                        })
                        .collect(Collectors.toList());

                // LicenseData 리스트 설정
                List<LicenseData> licenseDataList = Arrays.stream(queryParams.get("license").split(","))
                        .map(licenseStr -> {
                            String[] fields = licenseStr.split(";");
                            return LicenseData.newBuilder()
                                    .setLicenseName(fields[0])
                                    .setLicenseInstitution(fields[1])
                                    .setAcquisitionYear(Long.parseLong(fields[2]))
                                    .build();
                        })
                        .collect(Collectors.toList());

                // AcademicData 리스트 설정
                List<AcademicData> academicDataList = Arrays.stream(queryParams.get("academic").split(","))
                        .map(academicStr -> {
                            String[] fields = academicStr.split(";");
                            return AcademicData.newBuilder()
                                    .setEnrollmentType(EnrollmentType.valueOf(fields[0].toUpperCase()))
                                    .setAcademicType(AcademicType.valueOf(fields[1].toUpperCase()))
                                    .setSchoolName(fields[2])
                                    .setMajor(fields[3])
                                    .setEntranceDate(fields[4])
                                    .setGraduateDate(fields[5])
                                    .build();
                        })
                        .collect(Collectors.toList());

                // 기타 데이터 설정
                String supportRequirement = queryParams.get("supportRequirement");
                String selfIntroduction = queryParams.get("selfIntroduction");

                // OnboardingData 설정
                OnboardingData onboarding = OnboardingData.newBuilder()
                        .setUserId(Long.parseLong(userId))
                        .setDisabilityType(disabilityType)
                        .setLocationType(locationType)
                        .setIsTransit(isTransit)
                        .addAllEmploymentHistoryDatas(employmentHistoryDataList)
                        .addAllLicenseDatas(licenseDataList)
                        .addAllAcademicDatas(academicDataList)
                        .setSupportRequirement(supportRequirement)
                        .setSelfIntroduction(selfIntroduction)
                        .build();

                OnboardingRequest request = OnboardingRequest.newBuilder()
                        .setOnboarding(onboarding)
                        .build();

                return callCreateOnboarding(request, stub).block(); // 비동기 블록 대체

            }
            return new byte[0];
        });
    }


    private Mono<byte[]> callCreateOnboarding(OnboardingRequest request, OnboardingServiceGrpc.OnboardingServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<OnboardingResponse> observer = new SimpleStreamObserver<>();
            stub.createOnboarding(request, observer);
            return observer.getResponse(); // 응답 데이터 바로 반환
        });
    }

    private Mono<byte[]> makeGrpcCallForResume(String userId, String path, Map<String, String> queryParams, ResumeServiceGrpc.ResumeServiceStub stub) {
        return Mono.fromCallable(() -> {
            if (path.contains("/resume/create")) {
                ResumeData resumeData = buildResumeDataFromQueryParams(queryParams);
                ResumeCreateRequest request = ResumeCreateRequest.newBuilder()
                        .setResume(resumeData)
                        .build();
                return callCreateResume(request, stub).block();
            } else if (path.contains("/resume/update")) {
                ResumeData resumeData = buildResumeDataFromQueryParams(queryParams);
                ResumeUpdateRequest request = ResumeUpdateRequest.newBuilder()
                        .setResumeId(Long.parseLong(queryParams.get("resumeId")))
                        .setResume(resumeData)
                        .build();
                return callUpdateResume(request, stub).block();
            } else if (path.contains("/resume/download")) {
                ResumeDownloadRequest request = ResumeDownloadRequest.newBuilder()
                        .setResumeId(Long.parseLong(queryParams.get("resumeId")))
                        .build();
                return callDownloadResume(request, stub).block();
            }
            return new byte[0];
        });
    }

    private ResumeData buildResumeDataFromQueryParams(Map<String, String> queryParams) {
        List<QuestionData> questionDataList = Arrays.stream(queryParams.get("questions").split(","))
                .map(questionStr -> {
                    String[] fields = questionStr.split(";");
                    return QuestionData.newBuilder()
                            .setQuestion(fields[0])
                            .setAnswer(fields[1])
                            .build();
                })
                .collect(Collectors.toList());

        return ResumeData.newBuilder()
                .setUserId(Long.parseLong(queryParams.get("userId")))
                .setResumeName(queryParams.get("resumeName"))
                .setExperienced(Boolean.parseBoolean(queryParams.get("experienced")))
                .setWorkExperience(queryParams.get("workExperience"))
                .addAllQuestionDatas(questionDataList)
                .build();
    }


    private Mono<byte[]> callCreateResume(ResumeCreateRequest request, ResumeServiceGrpc.ResumeServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<ResumeResponse> observer = new SimpleStreamObserver<>();
            stub.createResume(request, observer);
            return observer.getResponse();
        });
    }

    private Mono<byte[]> callUpdateResume(ResumeUpdateRequest request, ResumeServiceGrpc.ResumeServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<ResumeResponse> observer = new SimpleStreamObserver<>();
            stub.updateResume(request, observer);
            return observer.getResponse();
        });
    }

    private Mono<byte[]> callDownloadResume(ResumeDownloadRequest request, ResumeServiceGrpc.ResumeServiceStub stub) {
        return Mono.fromCallable(() -> {
            SimpleStreamObserver<ResumeDownloadResponse> observer = new SimpleStreamObserver<>();
            stub.downloadResumeRdf(request, observer);
            return observer.getResponse();
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
