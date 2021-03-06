package com.msst.platform.service;

import com.msst.platform.MsstPlatformApp;
import com.msst.platform.config.Constants;
import com.msst.platform.domain.User;
import com.msst.platform.repository.reactive.UserRepository;
import com.msst.platform.service.dto.UserDTO;
import com.msst.platform.service.util.RandomUtil;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for the UserResource REST controller.
 *
 * @see UserService
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = MsstPlatformApp.class)
public class UserServiceIntTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    private User user;

    @Before
    public void init() {
        userRepository.deleteAll().block();
        user = new User();
        user.setLogin("johndoe");
        user.setPassword(RandomStringUtils.random(60));
        user.setActivated(true);
        user.setEmail("johndoe@localhost");
        user.setFirstName("john");
        user.setLastName("doe");
        user.setImageUrl("http://placehold.it/50x50");
        user.setLangKey("en");
    }

    @Test
    public void assertThatUserMustExistToResetPassword() {
        userRepository.save(user).block();
        Optional<User> maybeUser = userService.requestPasswordReset("invalid.login@localhost").blockOptional();
        assertThat(maybeUser).isNotPresent();

        maybeUser = userService.requestPasswordReset(user.getEmail()).blockOptional();
        assertThat(maybeUser).isPresent();
        assertThat(maybeUser.orElse(null).getEmail()).isEqualTo(user.getEmail());
        assertThat(maybeUser.orElse(null).getResetDate()).isNotNull();
        assertThat(maybeUser.orElse(null).getResetKey()).isNotNull();
    }

    @Test
    public void assertThatOnlyActivatedUserCanRequestPasswordReset() {
        user.setActivated(false);
        userRepository.save(user).block();

        Optional<User> maybeUser = userService.requestPasswordReset(user.getLogin()).blockOptional();
        assertThat(maybeUser).isNotPresent();
        userRepository.delete(user).block();
    }

    @Test
    public void assertThatResetKeyMustNotBeOlderThan24Hours() {
        Instant daysAgo = Instant.now().minus(25, ChronoUnit.HOURS);
        String resetKey = RandomUtil.generateResetKey();
        user.setActivated(true);
        user.setResetDate(daysAgo);
        user.setResetKey(resetKey);
        userRepository.save(user).block();

        Optional<User> maybeUser = userService.completePasswordReset("johndoe2", user.getResetKey()).blockOptional();
        assertThat(maybeUser).isNotPresent();
        userRepository.delete(user).block();
    }

    @Test
    public void assertThatResetKeyMustBeValid() {
        Instant daysAgo = Instant.now().minus(25, ChronoUnit.HOURS);
        user.setActivated(true);
        user.setResetDate(daysAgo);
        user.setResetKey("1234");
        userRepository.save(user).block();

        Optional<User> maybeUser = userService.completePasswordReset("johndoe2", user.getResetKey()).blockOptional();
        assertThat(maybeUser).isNotPresent();
        userRepository.delete(user).block();
    }

    @Test
    public void assertThatUserCanResetPassword() {
        String oldPassword = user.getPassword();
        Instant daysAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        String resetKey = RandomUtil.generateResetKey();
        user.setActivated(true);
        user.setResetDate(daysAgo);
        user.setResetKey(resetKey);
        userRepository.save(user).block();

        Optional<User> maybeUser = userService.completePasswordReset("johndoe2", user.getResetKey()).blockOptional();
        assertThat(maybeUser).isPresent();
        assertThat(maybeUser.orElse(null).getResetDate()).isNull();
        assertThat(maybeUser.orElse(null).getResetKey()).isNull();
        assertThat(maybeUser.orElse(null).getPassword()).isNotEqualTo(oldPassword);

        userRepository.delete(user).block();
    }

    @Test
    public void testFindNotActivatedUsersByCreationDateBefore() {
        Instant now = Instant.now();
        user.setActivated(false);
        User dbUser = userRepository.save(user).block();
        dbUser.setCreatedDate(now.minus(4, ChronoUnit.DAYS));
        userRepository.save(user).block();
        List<User> users = userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(now.minus(3, ChronoUnit.DAYS))
            .collectList().block();
        assertThat(users).isNotEmpty();
        userService.removeNotActivatedUsers();
        users = userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(now.minus(3, ChronoUnit.DAYS))
            .collectList().block();
        assertThat(users).isEmpty();
    }

    @Test
    public void assertThatAnonymousUserIsNotGet() {
        user.setLogin(Constants.ANONYMOUS_USER);
        if (!userRepository.findOneByLogin(Constants.ANONYMOUS_USER).blockOptional().isPresent()) {
            userRepository.save(user).block();
        }
        final PageRequest pageable = PageRequest.of(0, (int) userRepository.count().block().intValue());
        final List<UserDTO> allManagedUsers = userService.getAllManagedUsers(pageable)
            .collectList().block();
        assertThat(allManagedUsers.stream()
            .noneMatch(user -> Constants.ANONYMOUS_USER.equals(user.getLogin())))
            .isTrue();
    }


    @Test
    public void testRemoveNotActivatedUsers() {

        user.setActivated(false);
        userRepository.save(user).block();
        // Let the audit first set the creation date but then update it
        user.setCreatedDate(Instant.now().minus(30, ChronoUnit.DAYS));
        userRepository.save(user).block();

        assertThat(userRepository.findOneByLogin("johndoe").blockOptional()).isPresent();
        userService.removeNotActivatedUsers();
        assertThat(userRepository.findOneByLogin("johndoe").blockOptional()).isNotPresent();
    }

}
