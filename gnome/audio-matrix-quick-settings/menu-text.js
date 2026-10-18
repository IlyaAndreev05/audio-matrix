import GLib from 'gi://GLib';

export const TEXT = {
    ru: {
        title: 'Audio Matrix', connecting: 'Подключение к движку…', unavailable: 'Движок Audio Matrix недоступен',
        outputUnavailable: 'Устройство вывода недоступно',
        outputs: 'УСТРОЙСТВА ВЫВОДА', applications: 'ПРИЛОЖЕНИЯ', open: 'Эквалайзер и маршруты — открыть редактор',
        volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук', streams: 'Потоки',
        profile: 'Профиль', route: 'Маршрут', noApplications: 'Нет активных приложений', noMixerInputs: 'Нет входов микшера',
        controls: 'ЗАКРЕПЛЁННЫЕ НАСТРОЙКИ', enabled: 'Включено', disabled: 'Выключено',
        matrix: 'Матрица', arrows: 'Стрелки', input: 'Вход ↓', activeLinks: 'Активные связи', changeLink: 'Связи',
        percent: 'Уровень громкости', commandError: 'Команда не выполнена',
        pinned: 'Закреплено', reorder: 'Перетащить группу', moveUp: 'Переместить группу выше', moveDown: 'Переместить группу ниже',
        onOff: 'Вкл. / Выкл.', bass: 'Басы', events: 'События',
        deviceStates: {
            ONLINE: 'Подключено', UNBOUND: 'Не привязано', OFFLINE: 'Недоступно',
            AMBIGUOUS: 'Неоднозначная привязка', CONFLICT: 'Конфликт устройства', IGNORED: 'Игнорируется',
        },
    },
    en: {
        title: 'Audio Matrix', connecting: 'Connecting to engine…', unavailable: 'Audio Matrix engine unavailable',
        outputUnavailable: 'Output device unavailable',
        outputs: 'OUTPUT DEVICES', applications: 'APPLICATIONS', open: 'EQ and routes — open editor',
        volume: 'Volume', mute: 'Mute', unmute: 'Unmute', streams: 'Streams',
        profile: 'Profile', route: 'Route', noApplications: 'No active applications', noMixerInputs: 'No Mixer inputs',
        controls: 'PINNED CONTROLS', enabled: 'Enabled', disabled: 'Disabled',
        matrix: 'Matrix', arrows: 'Arrows', input: 'Input ↓', activeLinks: 'Active connections', changeLink: 'Connections',
        percent: 'Volume level', commandError: 'Command failed',
        pinned: 'Pinned', reorder: 'Drag group to reorder', moveUp: 'Move group up', moveDown: 'Move group down',
        onOff: 'On / Off', bass: 'Bass', events: 'Events',
        deviceStates: {
            ONLINE: 'Connected', UNBOUND: 'Not bound', OFFLINE: 'Offline',
            AMBIGUOUS: 'Ambiguous binding', CONFLICT: 'Device conflict', IGNORED: 'Ignored',
        },
    },
};

export function systemLanguage() {
    return GLib.get_language_names().some(name => name.startsWith('ru')) ? 'ru' : 'en';
}
