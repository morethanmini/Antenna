package ssafy.a507.backend.domain.account.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.dto.UserSettingDtos.UserSettingRequest;
import ssafy.a507.backend.domain.account.dto.UserSettingDtos.UserSettingResponse;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.entity.UserSetting;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.account.repository.UserSettingRepository;

/** 환경 설정 조회·변경 (ANT-NOTIFY-02). 행이 없으면 기본값이고, 조회는 행을 만들지 않는다. */
@Service
@RequiredArgsConstructor
public class UserSettingService {

    private final UserSettingRepository settings;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public UserSettingResponse get(Long userId) {
        return UserSettingResponse.of(settings.findById(userId).orElseGet(UserSetting::defaults));
    }

    /** 있으면 덮어쓰고 없으면 만든다. PK 가 users 와 같아 한 사람에 한 행이다. */
    @Transactional
    public UserSettingResponse update(Long userId, UserSettingRequest req) {
        UserSetting setting = settings.findById(userId).orElseGet(() -> {
            User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
            return settings.save(UserSetting.defaultsFor(user));
        });
        setting.update(req.notifyVerdict(), req.notifySeason(), req.notifySocial(), req.notifyChannel(), req.chartView());
        return UserSettingResponse.of(setting);
    }
}
