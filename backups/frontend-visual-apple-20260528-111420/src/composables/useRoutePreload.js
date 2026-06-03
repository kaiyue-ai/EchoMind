export function useRoutePreload(router) {
  function preloadRoute(path) {
    const matchedRoutes = router.resolve(path).matched
    const matched = matchedRoutes[matchedRoutes.length - 1]
    const component = matched?.components?.default
    if (typeof component?.preload === 'function') {
      component.preload().catch(() => {})
    }
  }

  return { preloadRoute }
}
