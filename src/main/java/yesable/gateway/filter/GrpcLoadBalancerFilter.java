package yesable.gateway.filter;


import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

@Component
public class GrpcLoadBalancerFilter extends AbstractGatewayFilterFactory<Object> {

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            Response<ServiceInstance> response = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);
            if (response != null) {
                ServiceInstance instance = response.getServer();
                if (instance != null) {
                    Map<String, String> metadata = instance.getMetadata();
                    String grpcPort = metadata.get("gRPC_port");

                    if (grpcPort == null) {
                        throw new IllegalStateException("No gRPC port found in service instance metadata");
                    }

                    URI originalUri = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                    URI grpcUri = URI.create(originalUri.getScheme() + "://" + originalUri.getHost() + ":" + grpcPort + originalUri.getPath());

                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, grpcUri);
                }
            }
            return chain.filter(exchange);
        };
    }
}

