const BUILTIN_SOURCES = [{ id: 'hongguo', label: '红果' }];

function getMediaSources(settings) {
  return [
    ...BUILTIN_SOURCES,
    ...(settings.custom_sources || []).filter(source => source.enabled).map(source => ({ id: `custom:${source.id}`, label: source.name, custom: true })),
  ];
}

function setBuiltinSourceEnabled(settings, id, enabled) {
  if (id === 'hongguo') throw new Error('红果来源固定开启，不能关闭');
  throw new Error('来源开关无效');
}

module.exports = { getMediaSources, setBuiltinSourceEnabled };
