package top.kdla.framework.llm.mentor.know.engine.business.service;

import top.kdla.framework.llm.mentor.know.engine.chat.entity.ChatParam;
import top.kdla.framework.llm.mentor.know.engine.rag.constant.RoleEnum;

public interface UserRoleService {

    public RoleEnum getUserRole(ChatParam chatParam);
}
