package io.github.opensabre.iqc.task.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.task.model.TaskItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskItemMapper extends BaseMapper<TaskItem> { }
