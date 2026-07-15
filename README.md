# br.com.aurepay:aurepay

SDK oficial da API AurePay para Java.

## Instalação

```xml
<dependency>
  <groupId>br.com.aurepay</groupId>
  <artifactId>aurepay</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Uso

```java
import com.aurepay.AurePay;
import java.util.Map;

AurePay aure = new AurePay("YOUR_API_KEY", "YOUR_API_SECRET");

aure.deposits.create(Map.of("amount", 10000, "method", "pix"));
aure.webhooks.list();
aure.company.balance();
```

Docs: https://api.aurepay.com.br/docs/sdks  
OpenAPI: https://api.aurepay.com.br/openapi.yaml
