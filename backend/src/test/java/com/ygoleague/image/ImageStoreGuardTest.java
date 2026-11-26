package com.ygoleague.image;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ImageStoreGuardTest {

    @Test
    void localStoreIsRefusedButOtherStoresPass() {
        assertThrows(IllegalStateException.class, () -> ImageStoreGuard.check("local"));
        assertThrows(IllegalStateException.class, () -> ImageStoreGuard.check(" LOCAL "));
        assertDoesNotThrow(() -> ImageStoreGuard.check("r2"));
    }

    @Test
    void defaultsToLocalWhenUnsetSoProdMustChooseExplicitly() {
        assertThrows(IllegalStateException.class, () -> new ImageStoreGuard(new MockEnvironment()));
        assertDoesNotThrow(() -> new ImageStoreGuard(new MockEnvironment().withProperty("app.images.store", "r2")));
    }

    @Test
    void onlyActiveUnderTheProdProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ImageStoreGuard.class)
                .withPropertyValues("app.images.store=local");
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ImageStoreGuard.class));
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("isn't allowed with the 'prod' profile"));
    }
}
