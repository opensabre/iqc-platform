package io.github.opensabre.iqc.shared;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/** IQC list response contract; keeps frontend pagination independent from MyBatis-Plus internals. */
public record IqcPage<T>(List<T> records, long current, long size, long total) {
    public static <T> IqcPage<T> from(IPage<T> page) {
        return new IqcPage<>(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }
}
