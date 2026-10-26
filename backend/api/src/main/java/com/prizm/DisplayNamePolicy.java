package com.prizm;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

public final class DisplayNamePolicy {
    public static final long INTERVAL = Duration.ofDays(7).toMillis();
    public static final List<String> RESERVED = List.of("관리자", "어드민", "운영자", "운영진", "운영팀", "관리팀", "공식계정", "admin", "administrator", "moderator", "staff");
    public static final String PATTERN = "^[\\p{L}\\p{N} _.-]+$";
    private DisplayNamePolicy() {}
    public static Map<String,Object> rules() {
        return Map.of("minLength",2,"maxLength",24,"reserved",RESERVED,"pattern",PATTERN,"changeIntervalDays",7);
    }
    public static String validate(String input) {
        String name=Normalizer.normalize(Objects.toString(input,""),Normalizer.Form.NFKC).strip();
        int size=name.codePointCount(0,name.length());
        if(size<2 || size>24 || !name.matches(PATTERN))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"표시 이름은 2~24자의 문자·숫자·공백·밑줄·마침표·하이픈으로 입력해 주세요");
        String compact=name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        if(RESERVED.stream().anyMatch(compact::contains))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"관리자·어드민·운영자 등 운영진을 사칭할 수 있는 표시 이름은 사용할 수 없습니다");
        return name;
    }
    public static void checkInterval(String oldName, String name, Long changedAt, long now) {
        if(oldName!=null && !oldName.equals(name) && changedAt!=null && now-changedAt<INTERVAL)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"표시 이름은 마지막 등록·변경 후 7일이 지나야 변경할 수 있습니다");
    }
}
