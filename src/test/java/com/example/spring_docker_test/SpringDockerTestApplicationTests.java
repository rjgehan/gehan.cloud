package com.example.spring_docker_test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(properties = {
		"app.security.pin=12345678",
		"spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class SpringDockerTestApplicationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserAccountRepository users;

	@Test
	void contextLoads() {
	}

	@Test
	void anonymousUsersAreRedirectedFromHomeToLogin() throws Exception {
		mvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void loginPageIsPublicAndDoesNotOfferAccountCreation() throws Exception {
		mvc.perform(get("/login"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Sign in")))
				.andExpect(content().string(containsString("data-digit=\"1\"")))
				.andExpect(content().string(not(containsString("Username"))))
				.andExpect(content().string(not(containsString("Create account"))));
	}

	@Test
	void authenticatedUsersCanSeeHomePage() throws Exception {
		mvc.perform(get("/").with(user("family")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("data-target=\"home\"")))
				.andExpect(content().string(containsString("data-target=\"weather\"")))
				.andExpect(content().string(containsString("Lights")))
				.andExpect(content().string(containsString("Sign out")));
	}

	@Test
	void userManagementUiIsNotAvailable() throws Exception {
		mvc.perform(get("/admin/users").with(user("family").roles("USER")))
				.andExpect(status().isForbidden());
	}

	@Test
	void pinLoginAuthenticatesFamilyAccount() throws Exception {
		mvc.perform(formLogin().user("family").password("00000000"))
				.andExpect(unauthenticated());

		mvc.perform(formLogin().user("family").password("12345678"))
				.andExpect(authenticated().withUsername("family"));
	}

	@Test
	void bootstrapFamilyAccountIsCreatedWithHashedPin() {
		UserAccount account = users.findByUsernameIgnoreCase("family").orElseThrow();

		org.hamcrest.MatcherAssert.assertThat(account.getPasswordHash(), notNullValue());
		org.hamcrest.MatcherAssert.assertThat(account.getPasswordHash(), not(containsString("12345678")));
	}

	@Test
	void resetPinCanCreateMissingFamilyAccount() {
		users.deleteAll();

		AdminUserInitializer initializer = new AdminUserInitializer(
				users,
				new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(),
				"family",
				"",
				"87654321");
		initializer.run();

		UserAccount account = users.findByUsernameIgnoreCase("family").orElseThrow();
		org.hamcrest.MatcherAssert.assertThat(account.getPasswordHash(), not(containsString("87654321")));
	}

}
