package io.github.opensabre.iqc.task.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskExecutionMapper extends BaseMapper<TaskExecution> { }
