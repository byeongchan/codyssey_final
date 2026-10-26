package com.prizm;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/** Local taxonomy chooses a category; raw product names are never sent as news queries. */
public final class NewsCategory {
    public record Topic(String category, List<String> keywords, String basis) {}
    private static final JsonNode TOPICS;
    static {
        try(var stream=NewsCategory.class.getResourceAsStream("/news-categories.json")) {
            TOPICS=new ObjectMapper().readTree(Objects.requireNonNull(stream));
        } catch(Exception ex) {throw new ExceptionInInitializerError(ex);}
    }
    private NewsCategory() {}
    private static Topic match(String text, String basis) {
        String value=Objects.toString(text,"").toLowerCase(Locale.ROOT);
        for(var row:TOPICS) for(var alias:row.path("keywords")) {
            String keyword=alias.asText().toLowerCase(Locale.ROOT);
            boolean matches=keyword.matches(".*[가-힣].*")?value.replaceAll("\\s+","").contains(keyword.replaceAll("\\s+",""))
                :Pattern.compile("(?<![a-z0-9])"+Pattern.quote(keyword)+"(?![a-z0-9])").matcher(value).find();
            if(matches) {
                var terms=new ArrayList<String>();for(var term:row.path("keywords")) terms.add(term.asText());
                return new Topic(row.path("category").asText(),List.copyOf(terms),basis);
            }
        }
        return null;
    }
    public static Topic resolve(String storedCategory, String title, Collection<String> discoveryIds) {
        Topic topic=match(storedCategory,"STORED_CATEGORY"); if(topic!=null) return topic;
        topic=match(title,"PRODUCT_TEXT_CATEGORY");if(topic!=null) return topic;
        if(Objects.toString(title,"").matches("(?is).*\\b(?:rtx|gtx|radeon)\\s*[-]?\\s*[0-9]{3,5}.*")) return match("그래픽카드","PRODUCT_TEXT_CATEGORY");
        var names=Map.of("pc","컴퓨터","digital","디지털기기","appliances","가전제품","fashion","패션","hobby","레저용품");
        var known=new TreeSet<String>();for(String id:discoveryIds) if(names.containsKey(id)) known.add(id);
        return known.size()==1?match(names.get(known.first()),"DISCOVERY_CATEGORY"):null;
    }
}
