package icu.weierstrass.vault7;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class BackupPlan {
    enum Mode {
        DATE, SIZE, EVERY_N_DAYS, BATCH_SIZE;

        boolean sizeLimited() {
            return this == SIZE || this == BATCH_SIZE;
        }
    }

    final Mode mode;
    final LocalDate start;
    final LocalDate end;
    final int days;
    final long limit;
    final ZoneId zone;

    BackupPlan(Mode mode, LocalDate start, LocalDate end, int days, long limit, ZoneId zone) {
        if (mode == Mode.DATE && (start == null || end == null)) {
            throw new IllegalArgumentException("请选择起始日期和结束日期");
        }
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("结束日期应晚于或等于起始日期");
        }
        if (mode == Mode.EVERY_N_DAYS && days < 1) {
            throw new IllegalArgumentException("请输入大于零的天数");
        }
        if (mode.sizeLimited() && limit < 1) {
            throw new IllegalArgumentException("请输入大于零的大小上限");
        }
        this.mode = mode;
        this.start = start;
        this.end = end;
        this.days = days;
        this.limit = limit;
        this.zone = zone;
    }

    LocalDate date(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    boolean includes(LocalDate date) {
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
    }

    long window(LocalDate anchor, LocalDate date) {
        return (date.toEpochDay() - anchor.toEpochDay()) / days;
    }
}
