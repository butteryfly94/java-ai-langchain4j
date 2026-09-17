package com.mayaping.ai.langchain4j.service;

import com.mayaping.ai.langchain4j.entity.Appointment;
import com.baomidou.mybatisplus.extension.service.IService;

public interface AppointmentService extends IService<Appointment> {
    Appointment getOne(Appointment appointment);
}
