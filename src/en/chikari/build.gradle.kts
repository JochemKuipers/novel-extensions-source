import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
	alias(kei.plugins.extension)
}

keiyoushi {
	name = "Chikari"
	versionCode = 1
	contentWarning = ContentWarning.SAFE
	libVersion = "1.6"

	source {
		baseUrl = "https://chikari.moe"
		lang = "en"
	}

	deeplink {
		path("/..*")
	}
}
