package com.eric.thumb.model.enums;

import lombok.Getter;

/**
 * Lua脚本执行状态枚举
 * 
 * @author Eric
 */
@Getter
public enum LuaStatusEnum {  
    // 成功  
    SUCCESS(1L),  
    // 失败  
    FAIL(-1L),  
    ;  
  
    private final long value;  
  
    LuaStatusEnum(long value) {  
        this.value = value;  
    }  
}
