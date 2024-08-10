# Application.yml
해당 폴더는 스프링 부트를 실행할 때 관리해야하는 환경변수들을 가지고 있는 패키지입니다.

```
server:
  port: 

spring:
  application:
    name: 
  cloud:
    gateway:
      routes:
        - id: 
          uri: 
          predicates:
            - 
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: 
```
위 yml파일은 해당 서버에서 security에 숨겨져 있습니다. 
파일은 노션에 **application.yml 버전 관리** 에 들어가서 확인하세요.
