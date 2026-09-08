package top.kdla.framework.llm.mentor.know.engine.business.service.impl;

import top.kdla.framework.llm.mentor.know.engine.business.entity.UserInfo;
import top.kdla.framework.llm.mentor.know.engine.business.mapper.UserInfoMapper;
import top.kdla.framework.llm.mentor.know.engine.business.service.UserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 客户信息表 Service 实现类
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {
}
