/* 加密助手官网脚本：设备识别直链下载、滚动出现动效、数字递增、滚动进度。
   不加载任何第三方资源，不发网络请求，不写 Cookie 与 localStorage。 */
(function () {
  'use strict';

  var APK = 'https://github.com/Pasokontukau/asymmetric-crypto-tool-release/releases/download/v1.4.0/CryptoAssistant-1.4.0-release.apk';
  var INSTALLER = 'https://github.com/Pasokontukau/asymmetric-crypto-tool-release/releases/download/v1.4.0/CryptoAssistant-1.4.0-installer.exe';

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- 设备识别 ---------- */
  var ua = navigator.userAgent || '';
  var iPadOS = /Macintosh/.test(ua) && (navigator.maxTouchPoints || 0) > 1;
  var platform = 'unknown';
  if (/Android/i.test(ua)) {
    platform = 'android';
  } else if (/iPhone|iPad|iPod/i.test(ua) || iPadOS) {
    platform = 'ios';
  } else if (/Windows/i.test(ua)) {
    platform = 'windows';
  } else if (/Mac OS X|Macintosh/i.test(ua)) {
    platform = 'mac';
  } else if (/Linux|X11|CrOS/i.test(ua)) {
    platform = 'linux';
  }
  document.documentElement.setAttribute('data-platform', platform);

  var picks = {
    android: {
      href: APK,
      label: '下载 Android 版 APK · 18.7 MB',
      card: 'android',
      short: '已按你的设备选中 Android 版（18.7 MB）',
      banner: '已识别为 Android 设备：上面的主按钮直接下载 APK，不需要打开 GitHub。装好后可用「打开方式」或「分享」把文件交给它。'
    },
    windows: {
      href: INSTALLER,
      label: '下载 Windows 安装包 · 42.8 MB',
      card: 'windows',
      short: '已按你的设备选中 Windows 安装版（42.8 MB）',
      banner: '已识别为 Windows 设备：主按钮直接下载安装包（需要管理员权限）。不想装进系统，就用下面的便携 ZIP，解压即用。'
    },
    ios: {
      href: '#download',
      label: '查看支持的平台',
      short: 'iOS 暂无版本，可查看 Android 与 Windows 版',
      banner: '检测到 iOS 设备：目前没有 iOS 版本。可以看看 Android 与 Windows 版，或到「参与开发」推动 iOS 支持。'
    },
    mac: {
      href: '#download',
      label: '查看支持的平台',
      short: 'macOS 暂无版本，桌面端仅 Windows x64',
      banner: '检测到 macOS：桌面端目前只提供 Windows x64。可以看看 Android 版，或在 Windows 虚拟机里使用便携包。'
    },
    linux: {
      href: '#download',
      label: '查看支持的平台',
      short: 'Linux 暂无版本，桌面端仅 Windows x64',
      banner: '检测到 Linux：桌面端目前只提供 Windows x64。可以看看 Android 版，或到「参与开发」推动 Linux 支持。'
    },
    unknown: {
      href: '#download',
      label: '选择你的平台',
      short: '',
      banner: '没能识别你的设备，请在下面手动选择平台。'
    }
  };

  var pick = picks[platform] || picks.unknown;

  function setDownloadLink(el, href, label) {
    if (!el) return;
    el.setAttribute('href', href);
    el.innerHTML = label + ' <span aria-hidden="true">' + (href.charAt(0) === '#' ? '↓' : '↓') + '</span>';
  }

  setDownloadLink(document.getElementById('hero-download'), pick.href, pick.label);
  var headerCta = document.getElementById('header-download');
  if (headerCta && pick.href.charAt(0) !== '#') {
    headerCta.setAttribute('href', pick.href);
    headerCta.innerHTML = '下载 <span>1.4.0</span>';
  }

  var heroPick = document.getElementById('hero-pick');
  if (heroPick && pick.short) {
    heroPick.textContent = pick.short;
    heroPick.hidden = false;
  }

  var banner = document.getElementById('pick-banner');
  if (banner) {
    banner.textContent = pick.banner;
    banner.hidden = false;
  }

  if (pick.card) {
    var card = document.querySelector('[data-platform-card="' + pick.card + '"]');
    if (card) {
      card.classList.add('is-recommended');
      var downloadSection = document.getElementById('download');
      if (downloadSection) downloadSection.setAttribute('data-pick', pick.card);
    }
  }

  /* ---------- 移动端菜单点完自动收起 ---------- */
  Array.prototype.forEach.call(document.querySelectorAll('.mobile-nav-panel a'), function (a) {
    a.addEventListener('click', function () {
      var d = a.closest('details');
      if (d) d.open = false;
    });
  });

  /* ---------- 滚动出现 ---------- */
  var revealItems = Array.prototype.slice.call(document.querySelectorAll('[data-reveal]'));
  if (!('IntersectionObserver' in window) || reduceMotion) {
    revealItems.forEach(function (el) { el.classList.add('is-visible'); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    revealItems.forEach(function (el) { io.observe(el); });
  }

  /* ---------- 数字递增 ---------- */
  function countUp(el) {
    var target = parseInt(el.getAttribute('data-count'), 10);
    if (isNaN(target)) return;
    if (reduceMotion || target === 0) {
      el.textContent = String(target);
      return;
    }
    var started = null;
    function frame(ts) {
      if (started === null) started = ts;
      var p = Math.min((ts - started) / 900, 1);
      var eased = 1 - Math.pow(1 - p, 3);
      el.textContent = String(Math.round(target * eased));
      if (p < 1) window.requestAnimationFrame(frame);
    }
    window.requestAnimationFrame(frame);
  }

  var counters = Array.prototype.slice.call(document.querySelectorAll('[data-count]'));
  if (!('IntersectionObserver' in window)) {
    counters.forEach(countUp);
  } else {
    var co = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          countUp(entry.target);
          co.unobserve(entry.target);
        }
      });
    }, { threshold: 0.6 });
    counters.forEach(function (el) { co.observe(el); });
  }

  /* ---------- 顶部滚动进度 ---------- */
  var bar = document.querySelector('.scroll-progress span');
  if (bar) {
    var ticking = false;
    var update = function () {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      var ratio = max > 0 ? Math.min(window.scrollY / max, 1) : 0;
      bar.style.transform = 'scaleX(' + ratio + ')';
      ticking = false;
    };
    window.addEventListener('scroll', function () {
      if (!ticking) {
        ticking = true;
        window.requestAnimationFrame(update);
      }
    }, { passive: true });
    window.addEventListener('resize', update);
    update();
  }
})();
