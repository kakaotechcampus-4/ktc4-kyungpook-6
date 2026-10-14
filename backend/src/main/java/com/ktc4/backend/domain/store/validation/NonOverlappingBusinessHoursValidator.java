package com.ktc4.backend.domain.store.validation;

import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.util.BusinessTimes;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@link NonOverlappingBusinessHours} 를 검사한다.
 *
 * <p>줄마다 요일별 반열린 구간 {@code [여는 분, 닫는 분)} 으로 바꾼다. 자정을 넘긴 줄은 그날 {@code [여는, 1440)} 과
 * 다음 요일 {@code [0, 닫는)} 두 구간으로 쪼갠다. 그다음 요일마다 시작 순으로 정렬해, 앞선 구간들이 끝난 시각보다
 * 먼저 시작하는 구간이 있으면 겹침이다.
 *
 * <p>형식이 틀린 줄(빈 줄, 요일·시간이 없거나 틀린 줄, 여는 시간과 닫는 시간이 같은 줄)은 건너뛴다. 목록 안의
 * 제약들은 순서 없이 따로 돌아서 이런 줄도 여기 들어온다. 그 줄의 오류는 칸별 검증이 이미 내므로 여기서는
 * 통과로 치고, 예외도 던지지 않는다 — 검증 중 예외는 400 이 아니라 500 이 된다.
 */
public class NonOverlappingBusinessHoursValidator
        implements ConstraintValidator<NonOverlappingBusinessHours, List<BusinessHourRequest>> {

    private static final BusinessDay[] DAYS = BusinessDay.values();

    @Override
    public boolean isValid(List<BusinessHourRequest> hours, ConstraintValidatorContext context) {
        if (hours == null) {
            return true;
        }
        Map<BusinessDay, List<Span>> spansByDay = new EnumMap<>(BusinessDay.class);
        for (BusinessHourRequest hour : hours) {
            addSpans(hour, spansByDay);
        }
        return spansByDay.values().stream().noneMatch(NonOverlappingBusinessHoursValidator::hasOverlap);
    }

    private static void addSpans(BusinessHourRequest hour, Map<BusinessDay, List<Span>> spansByDay) {
        if (hour == null || hour.day() == null) {
            return;
        }
        OptionalInt open = BusinessTimes.toOpenMinute(hour.open());
        OptionalInt close = BusinessTimes.toCloseMinute(hour.close());
        if (open.isEmpty() || close.isEmpty() || open.getAsInt() == close.getAsInt()) {
            return;
        }
        int start = open.getAsInt();
        int end = close.getAsInt();
        if (start < end) {
            spansOf(hour.day(), spansByDay).add(new Span(start, end));
            return;
        }
        // 자정을 넘긴 줄 — 그날 자정까지와, 다음 요일 자정부터 닫는 시간까지
        spansOf(hour.day(), spansByDay).add(new Span(start, BusinessTimes.END_OF_DAY));
        spansOf(nextDay(hour.day()), spansByDay).add(new Span(0, end));
    }

    private static List<Span> spansOf(BusinessDay day, Map<BusinessDay, List<Span>> spansByDay) {
        return spansByDay.computeIfAbsent(day, ignored -> new ArrayList<>());
    }

    private static BusinessDay nextDay(BusinessDay day) {
        return DAYS[(day.ordinal() + 1) % DAYS.length];
    }

    private static boolean hasOverlap(List<Span> spans) {
        List<Span> sorted = spans.stream().sorted(Comparator.comparingInt(Span::start)).toList();
        int latestEnd = -1;
        for (Span span : sorted) {
            if (span.start() < latestEnd) {
                return true;
            }
            latestEnd = Math.max(latestEnd, span.end());
        }
        return false;
    }

    // 한 요일 안의 반열린 구간 [start, end). 끝과 다음 시작이 같으면 맞닿은 것이지 겹친 것이 아니다.
    private record Span(int start, int end) {
    }
}
