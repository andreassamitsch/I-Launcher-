package com.andreassamitsch.joyntv

import java.security.MessageDigest

internal object JoynProtocol {
    const val graphQlUrl = "https://api.joyn.de/graphql"
    const val authBaseUrl = "https://auth.joyn.de/auth"
    const val entitlementUrl = "https://entitlements-service-alb.prd.platform.s.joyn.de/api/user/entitlement-token"
    const val playbackBaseUrl = "https://api.vod-prd.s.joyn.de/v1"

    // Protocol constant used by Joyn's web playback handshake. It is not a DRM key.
    private const val signatureSuffix =
        "3543373833383336354337383634363635433738363633383236354337383330363435393543373833393335323435433738363533393543373833383332334635433738363633333344334235433738333836363335"

    const val playerPayload =
        "{\"manufacturer\":\"unknown\",\"platform\":\"browser\",\"maxSecurityLevel\":1,\"model\":\"unknown\",\"protectionSystem\":\"widevine\",\"streamingFormat\":\"dash\",\"enableSubtitles\":true,\"maxResolution\":1080,\"version\":\"v1\"}"

    val liveStreamsQuery = """
        query PlayerLivestreams {
          liveStreams(filterLivestreamsTypes: [EVENT, LINEAR], first: 500, offset: 0) {
            id
            title
            type
            quality
            markings
            brand {
              id
              brandCode
              livestream { logo { url(profile: "nextgen-web-artlogo-183x75") } }
            }
            epgEvents {
              startDate
              endDate
              program {
                __typename
                ... on EpgEntry {
                  title
                  secondaryTitle
                  images { type url }
                }
                ... on Episode {
                  title
                  description
                  thumbnailImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-episodestillplayer-693x390")
                  }
                  posterImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-primarycut-1920x1080")
                  }
                }
                ... on Movie {
                  title
                  posterImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-primarycut-1920x1080")
                  }
                }
                ... on CompilationItem {
                  title
                  thumbnailImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-episodestillplayer-693x390")
                  }
                  posterImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-primarycut-1920x1080")
                  }
                }
                ... on Extra {
                  title
                  posterImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-primarycut-1920x1080")
                  }
                }
                ... on SportsMatch {
                  title
                  posterImage: image(type: PRIMARY) {
                    url(profile: "nextgen-web-primarycut-1920x1080")
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    fun playbackSignature(entitlementToken: String): String {
        val input = "$playerPayload,$entitlementToken$signatureSuffix"
        return MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
