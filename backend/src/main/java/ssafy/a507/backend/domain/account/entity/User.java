package ssafy.a507.backend.domain.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** 계정. 모든 도메인이 이 id로 회원을 참조한다. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    public enum Role {
        USER,
        ADMIN
    }

    /** 영구 차단만 둔다. BANNED면 로그인을 거부하되 기존 예측·원장은 남긴다. */
    public enum Status {
        ACTIVE,
        BANNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면 표시 이름이자 유일한 공개 식별자.
     *
     * <p>NULL 은 "온보딩에서 아직 정하지 않음"을 뜻한다. 임시 닉네임을 넣어 두면 온보딩을 중간에
     * 그만둔 회원을 다음 로그인에서 다시 온보딩으로 보낼 수 없다.
     */
    @Column(unique = true, length = 30)
    private String nickname;

    /** 프로필 소개 문구. 구독 판단 근거로 쓰인다. */
    @Column(length = 200)
    private String introduce;

    /**
     * 프로필 사진. upload_files.id 를 가리킨다.
     *
     * <p>연관(@ManyToOne)으로 두지 않는다 — 프로필을 읽을 때마다 업로드 행을 함께 끌고 오게
     * 되고, 파일이 지워지면 회원 조회가 통째로 깨진다. id 만 들고 있다가 못 찾으면 사진이
     * 없는 것으로 그린다. NULL 이면 한 번도 올리지 않았다는 뜻이다.
     */
    @Column(name = "avatar_file_id", length = 36)
    private String avatarFileId;

    /** SSAFY WALLET 연동 주소. 소문자로 정규화하며 NULL이면 예측을 등록할 수 없다. */
    @Column(name = "wallet_address", unique = true, length = 42)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** OAuth 최초 로그인 시 생성. 닉네임은 온보딩에서 정하므로 아직 비어 있다. */
    public static User create() {
        User user = new User();
        user.role = Role.USER;
        user.status = Status.ACTIVE;
        return user;
    }

    /** 온보딩·프로필 수정에서 닉네임을 확정한다. 중복 검사는 서비스가 먼저 한다. */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 프로필 소개를 바꾼다.
     *
     * <p>빈 문자열은 null 로 눕힌다 — "지웠다"와 "아직 안 썼다"는 화면에서 같은 상태이고, 둘을 따로
     * 저장하면 {@code introduce != null} 로 판단하는 쪽이 빈 문단을 그린다.
     */
    public void changeIntroduce(String introduce) {
        this.introduce = (introduce == null || introduce.isBlank()) ? null : introduce.strip();
    }

    /**
     * 프로필 사진을 바꾼다. null 이면 지운 것으로 본다 — 기본 그림으로 돌아간다.
     *
     * <p>파일이 내 것인지, 용도가 PROFILE 인지는 서비스가 먼저 본다. 엔티티는 통과한 값을 담는다.
     */
    public void changeAvatar(String fileId) {
        this.avatarFileId = fileId;
    }

    /** false 면 온보딩을 마치지 않은 회원이다. */
    public boolean hasNickname() {
        return nickname != null;
    }

    /**
     * 관리자 제재 (ANT-ADMIN-01). BANNED 로 내리면 {@code AuthService} 가 로그인을 거부한다 —
     * 기존 예측·원장은 그대로 남는다(엔티티 머리 주석).
     */
    public void changeStatus(Status next) {
        this.status = next;
    }

    /**
     * 지갑을 1회 연동한다. 주소는 소문자로 눕혀 저장한다 —
     * EIP-55 체크섬 주소와 소문자 주소는 같은 주소지만 문자열로는 달라서,
     * 정규화 없이 저장하면 unique 제약도 대조도 뚫린다.
     */
    public void linkWallet(String address) {
        this.walletAddress = address.toLowerCase(Locale.ROOT);
    }
}
