package yesable.gateway.constant;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ConverterConstants {
    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

    public Mono<ServiceInstance> chooseInstance(String serviceId) {
        ReactorServiceInstanceLoadBalancer loadBalancer = (ReactorServiceInstanceLoadBalancer) loadBalancerFactory.getInstance(serviceId);
        return Mono.defer(() -> loadBalancer.choose()
                .map(response -> response.hasServer() ? response.getServer() : null));
    }

}
