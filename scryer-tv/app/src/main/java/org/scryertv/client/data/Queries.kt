package org.scryertv.client.data

/**
 * Все GraphQL-запросы приложения в одном месте. Схема — `api/graphql/schema.graphql`
 * в репозитории Scryer; если сервер переименует поле, правится только этот файл.
 */
object Queries {

    private const val TITLE_CARD = """
        fragment TitleCard on TitlePayload {
          id name facet year overview posterUrl backgroundUrl monitored
          libraryName qualityTier sizeBytes episodesOwned episodesTotal
          contentStatus runtimeMinutes imdbId slug sortTitle
          ratings { rating }
        }
    """

    private const val DISCOVERY_CARD = """
        fragment DiscoveryCard on DiscoveryItemPayload {
          id targetKey targetKind resolved resolvedTitleId displayTitle sortTitle year
          posterUrl backgroundUrl overview contentType rating ownedInInput
          externalIds { source kind id }
        }
    """

    private const val METADATA_CARD = """
        fragment MetadataCard on MetadataSearchItemPayload {
          tvdbId smgId tmdbId imdbId name slug type year status overview
          posterUrl language runtimeMinutes sortTitle
          externalIds { source kind value }
        }
    """

    const val LOGIN = """
        mutation Login(${'$'}input: LoginInput!) {
          login(input: ${'$'}input) {
            token mfaEnrollmentRequired passwordChangeRequired
            user { username }
          }
        }
    """

    const val CREATE_API_KEY = """
        mutation CreateApiKey(${'$'}input: CreateMyApiKeyInput!) {
          createMyApiKey(input: ${'$'}input) { apiKey }
        }
    """

    const val SERVER_INFO = """
        query ServerInfo {
          scryerVersion
          me { username }
        }
    """

    // Счётчики берём из titles.totalCount: dashboardSummary доступен только администраторам.
    const val HOME = TITLE_CARD + """
        query Home(${'$'}limit: Int!) {
          movies: titles(facet: MOVIE, sort: { key: ADDED, direction: DESC }, limit: ${'$'}limit) {
            items { ...TitleCard }
            totalCount
          }
          series: titles(facet: SERIES, sort: { key: ADDED, direction: DESC }, limit: ${'$'}limit) {
            items { ...TitleCard }
            totalCount
          }
          anime: titles(facet: ANIME, sort: { key: ADDED, direction: DESC }, limit: ${'$'}limit) {
            items { ...TitleCard }
            totalCount
          }
        }
    """

    const val CATALOG = TITLE_CARD + """
        query Catalog(${'$'}facet: MediaFacetValue, ${'$'}query: String, ${'$'}sort: TitleCatalogSortInput,
                      ${'$'}limit: Int, ${'$'}offset: Int) {
          titles(facet: ${'$'}facet, query: ${'$'}query, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
            items { ...TitleCard }
            hasMore
            totalCount
          }
        }
    """

    const val TITLE = TITLE_CARD + DISCOVERY_CARD + """
        query Title(${'$'}id: ID!) {
          title(id: ${'$'}id) {
            ...TitleCard
            externalIds { source kind value }
            playbackLinks { displayName provider href }
            moreLikeThis(limit: 20) { ...DiscoveryCard }
          }
        }
    """

    const val DISCOVERY = DISCOVERY_CARD + """
        query Discovery(${'$'}limit: Int!) {
          discoveryHome(input: { limitPerSection: ${'$'}limit, includeUnresolved: true }) {
            personalizedSections { title items { ...DiscoveryCard } }
            publicSections { title items { ...DiscoveryCard } }
          }
        }
    """

    const val SEARCH_METADATA = METADATA_CARD + """
        query SearchMetadata(${'$'}query: String!, ${'$'}language: String!) {
          searchMetadataMulti(query: ${'$'}query, limit: 20, language: ${'$'}language) {
            movies { ...MetadataCard }
            series { ...MetadataCard }
            anime { ...MetadataCard }
          }
        }
    """

    const val QUEUE = """
        query Queue(${'$'}limit: Int!) {
          downloadQueuePage(limit: ${'$'}limit, scryerSubmittedOnly: false) {
            totalCount
            items {
              id titleId titleName facet clientName displayState progressPercent
              sizeBytes remainingSeconds attentionReason
              title { posterUrl backgroundUrl }
            }
          }
        }
    """

    const val CALENDAR = """
        query Calendar(${'$'}start: Date!, ${'$'}end: Date!) {
          calendarEpisodes(startDate: ${'$'}start, endDate: ${'$'}end) {
            id titleId titleName seasonNumber episodeNumber episodeTitle airDate
            imageUrl titleBackgroundUrl monitored
            mediaAvailability { state primaryQualityLabel }
          }
        }
    """

    const val LIBRARIES = """
        query Libraries(${'$'}facet: MediaFacetValue) {
          libraries(facet: ${'$'}facet) { id isDefault }
        }
    """

    const val ADD_TITLE = """
        mutation AddTitle(${'$'}input: AddTitleInput!) {
          addTitle(input: ${'$'}input) {
            reusedExistingTitle
            title { id }
          }
        }
    """

    const val SUBMIT_REQUEST = """
        mutation SubmitRequest(${'$'}input: SubmitMediaRequestInput!) {
          submitMediaRequest(input: ${'$'}input) { requestId }
        }
    """

    const val SET_MONITORED = """
        mutation SetMonitored(${'$'}input: SetTitleMonitoredInput!) {
          setTitleMonitored(input: ${'$'}input) { id monitored }
        }
    """

    const val QUEUE_BEST_RELEASE = """
        mutation QueueBestRelease(${'$'}input: QueueBestReleaseInput!) {
          queueBestRelease(input: ${'$'}input) {
            status sourceTitle
            conflict { __typename }
          }
        }
    """
}
