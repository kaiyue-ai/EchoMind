package com.echomind.memory.usermemory;

import java.util.Optional;

/** 用户画像快照存储。画像是事实层的压缩结果，不替代 Redis Stack 中的细粒度事实。 */
public interface UserProfileSnapshotStore {

    Optional<UserProfileSnapshot> get(String userId);

    void save(UserProfileSnapshot snapshot);

    static UserProfileSnapshotStore noop() {
        return new UserProfileSnapshotStore() {
            @Override
            public Optional<UserProfileSnapshot> get(String userId) {
                return Optional.empty();
            }

            @Override
            public void save(UserProfileSnapshot snapshot) {
            }
        };
    }
}
