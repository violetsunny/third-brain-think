package top.kdla.framework.llm.mentor.know.engine.business.service.impl;

import top.kdla.framework.llm.mentor.know.engine.business.constant.StaffStatus;
import top.kdla.framework.llm.mentor.know.engine.business.entity.MyCar;
import top.kdla.framework.llm.mentor.know.engine.business.entity.StaffInfo;
import top.kdla.framework.llm.mentor.know.engine.business.service.MyCarService;
import top.kdla.framework.llm.mentor.know.engine.business.service.StaffInfoService;
import top.kdla.framework.llm.mentor.know.engine.business.service.UserRoleService;
import top.kdla.framework.llm.mentor.know.engine.chat.constant.ChatSource;
import top.kdla.framework.llm.mentor.know.engine.chat.entity.ChatParam;
import top.kdla.framework.llm.mentor.know.engine.rag.constant.RoleEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserRoleServiceImpl implements UserRoleService {

    private static final String STAFF_LOGIN_PREFIX = "staff_";

    @Autowired
    private StaffInfoService staffInfoService;

    @Autowired
    private MyCarService myCarService;

    @Override
    public RoleEnum getUserRole(ChatParam chatParam) {
        if (chatParam.chatSource() == ChatSource.STAFF_DING) {
            return RoleEnum.CUSTOMER_SERVICE;
        }

        //再次查询一下车辆，避免水平权限漏洞
        MyCar myCar = myCarService.getCarByUser(chatParam.intentRecognitionResult().entities().car_id(), chatParam.userId());
        if (myCar != null) {
            return RoleEnum.OWNER;
        }

        StaffInfo staffInfo = null;
        String userId = chatParam.userId();
        if (userId != null && userId.startsWith(STAFF_LOGIN_PREFIX)) {
            String empId = userId.substring(STAFF_LOGIN_PREFIX.length());
            staffInfo = staffInfoService.getByEmpId(empId);
        }
        if (staffInfo != null && staffInfo.getStatus() == StaffStatus.ON_JOB) {
            return RoleEnum.CUSTOMER_SERVICE;
        }

        return RoleEnum.VISITOR;
    }
}
