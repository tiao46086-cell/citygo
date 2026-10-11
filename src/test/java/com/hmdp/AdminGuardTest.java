package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.AdminGuard;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AdminGuardTest {

    @AfterEach
    public void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    public void emptyListDeniesALoggedInUser() {
        AdminGuard guard = new AdminGuard("");
        saveUser(1013L);
        Result denied = guard.requireAdmin();
        Assertions.assertNotNull(denied);
        Assertions.assertEquals("无权限", denied.getErrorMsg());
    }

    @Test
    public void listedUserIsAllowed() {
        AdminGuard guard = new AdminGuard("1013, 1014");
        saveUser(1014L);
        Assertions.assertNull(guard.requireAdmin());
    }

    @Test
    public void blankEntriesAreIgnored() {
        AdminGuard guard = new AdminGuard(" , 8 , ");
        saveUser(8L);
        Assertions.assertNull(guard.requireAdmin());
    }

    private void saveUser(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
}
