package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class UsedClient {
    private final ObjectMapper mapper; private final String url, key;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public UsedClient(ObjectMapper mapper, @Value("${prizm.collector-url}") String url, @Value("${prizm.internal-key}") String key) { this.mapper=mapper; this.url=url; this.key=key; }
    public JsonNode read(String provider, String action, Map<String,Object> input) {
        UsedController.provider(provider);
        try {
            URI base=URI.create(url);
            if (!Set.of("search","detail","regions","categories").contains(action) || key.isBlank() || !"http".equals(base.getScheme())
                || !Set.of("127.0.0.1","localhost").contains(Objects.toString(base.getHost(),"")) || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null || !Set.of("","/").contains(base.getPath())) throw new IllegalArgumentException();
            var request=HttpRequest.newBuilder(base.resolve("/internal/used/"+provider+"/"+action)).timeout(Duration.ofSeconds(16))
                .header("Content-Type","application/json").header("X-Internal-Key",key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200) throw new ResponseStatusException(switch(response.statusCode()) { case 400->HttpStatus.BAD_REQUEST; case 404->HttpStatus.NOT_FOUND; case 429->HttpStatus.TOO_MANY_REQUESTS; default->HttpStatus.SERVICE_UNAVAILABLE; },"중고 수집기의 REEF_USED_ENABLED·API 키·연결 상태를 확인하세요");
                byte[] bytes=stream.readNBytes(2*1024*1024+1); if(bytes.length>2*1024*1024) throw new IllegalArgumentException();
                var data=mapper.readTree(bytes);
                if(!("reef-"+provider).equals(data.path("source").asText()) || !action.equals(data.path("action").asText()) || !provider.equals(data.path("provider").asText()) || !data.path("synthetic").isBoolean() || data.path("synthetic").asBoolean()) throw new IllegalArgumentException();
                DataPolicy.instant(data.path("receivedAt"),"receivedAt");
                if(!data.path("fetchedAt").isNull()) DataPolicy.instant(data.path("fetchedAt"),"fetchedAt");
                if("search".equals(action) && (!data.path("items").isArray() || data.path("items").size()>400)) throw new IllegalArgumentException();
                if("search".equals(action) && "karrot".equals(provider) && !Objects.equals(input.get("regionId"),data.path("regionId").asText())) throw new IllegalArgumentException();
                if("detail".equals(action) && !Objects.equals(input.get("productId"),data.path("product").path("productId").asText())) throw new IllegalArgumentException();
                return data;
            }
        } catch(ResponseStatusException ex) { throw ex; }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"중고 조회가 중단되었습니다"); }
        catch(Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"중고 수집기 연결·응답을 확인하세요"); }
    }
}
