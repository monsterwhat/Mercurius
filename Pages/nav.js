/* ============================================================
   Mercurius Documentation — Shared JavaScript
   Hamburger menu, copy buttons, scroll reveal, sidebar
   ============================================================ */

(function () {
  'use strict';

  /* --- Hamburger Menu --- */
  function initHamburger() {
    var btn = document.querySelector('.nav__hamburger');
    var menu = document.querySelector('.nav__mobile-menu');
    if (!btn || !menu) return;

    btn.addEventListener('click', function () {
      var isOpen = menu.classList.contains('open');
      menu.classList.toggle('open');
      btn.setAttribute('aria-expanded', !isOpen);

      // Swap SVG icon
      if (menu.classList.contains('open')) {
        btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>';
      } else {
        btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>';
      }
    });
  }

  /* --- Copy Button for Code Blocks --- */
  function initCopyButtons() {
    document.querySelectorAll('.code-block__copy').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var block = btn.closest('.code-block');
        var codeEl = block.querySelector('code') || block.querySelector('pre');
        if (!codeEl) return;

        var text = codeEl.textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(function () {
            showCopied(btn);
          });
        } else {
          // Fallback
          var textarea = document.createElement('textarea');
          textarea.value = text;
          textarea.style.position = 'fixed';
          textarea.style.opacity = '0';
          document.body.appendChild(textarea);
          textarea.select();
          document.execCommand('copy');
          document.body.removeChild(textarea);
          showCopied(btn);
        }
      });
    });
  }

  function showCopied(btn) {
    var original = btn.innerHTML;
    btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg> Copiado';
    btn.style.color = '#059669';
    btn.style.borderColor = '#059669';
    setTimeout(function () {
      btn.innerHTML = original;
      btn.style.color = '';
      btn.style.borderColor = '';
    }, 2000);
  }

  /* --- Scroll Reveal --- */
  function initScrollReveal() {
    if (typeof IntersectionObserver === 'undefined') return;

    var els = document.querySelectorAll('.reveal');
    if (els.length === 0) return;

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.1 });

    els.forEach(function (el) { observer.observe(el); });
  }

  /* --- Sidebar Toggle (API page) --- */
  function initSidebarToggle() {
    var toggle = document.querySelector('.sidebar-toggle');
    var sidebar = document.querySelector('.api-sidebar');
    var overlay = document.querySelector('.sidebar-overlay');
    if (!toggle || !sidebar) return;

    toggle.addEventListener('click', function () {
      sidebar.classList.toggle('open');
      if (overlay) overlay.classList.toggle('open');
    });

    if (overlay) {
      overlay.addEventListener('click', function () {
        sidebar.classList.remove('open');
        overlay.classList.remove('open');
      });
    }
  }

  /* --- Sidebar Collapsible Groups --- */
  function initSidebarGroups() {
    document.querySelectorAll('.api-sidebar__heading').forEach(function (heading) {
      heading.addEventListener('click', function () {
        var links = heading.nextElementSibling;
        if (!links) return;
        heading.classList.toggle('collapsed');
        links.classList.toggle('hidden');
      });
    });
  }

  /* --- Active Sidebar: expand current page group, highlight matching links --- */
  function initActiveSidebar() {
    var sidebar = document.querySelector('.api-sidebar');
    if (!sidebar) return;

    var currentPage = window.location.pathname.split('/').pop() || 'index.html';
    var hash = window.location.hash;
    var groups = sidebar.querySelectorAll('.api-sidebar__group');
    var anyMatch = false;

    groups.forEach(function (group) {
      var links = group.querySelectorAll('.api-sidebar__link');
      var groupHasMatch = false;

      links.forEach(function (link) {
        var href = link.getAttribute('href') || '';
        // Parse the href: could be "#section", "page.html#section"
        var hrefPage = currentPage;
        var hrefHash = href;
        if (href.indexOf('#') !== -1) {
          var parts = href.split('#');
          hrefPage = parts[0] || currentPage;
          hrefHash = '#' + parts[1];
        } else if (href.indexOf('.html') !== -1) {
          hrefPage = href;
          hrefHash = '';
        }

        // Does this link point to the current page?
        var isCurrentPage = (hrefPage === currentPage || hrefPage === '');

        if (isCurrentPage) {
          groupHasMatch = true;

          // Exact section match: current hash matches link hash
          if (hash && hrefHash === hash) {
            link.classList.add('api-sidebar__link--active');
            anyMatch = true;
          }
        }
      });

      // Expand groups that match the current page; collapse others
      if (groupHasMatch) {
        group.classList.remove('collapsed');
        var linksContainer = group.querySelector('.api-sidebar__links');
        if (linksContainer) linksContainer.classList.remove('hidden');
      } else {
        // Collapse groups pointing to other pages (only if they have a heading)
        var heading = group.querySelector('.api-sidebar__heading');
        var linksContainer = group.querySelector('.api-sidebar__links');
        if (heading) {
          heading.classList.add('collapsed');
          if (linksContainer) linksContainer.classList.add('hidden');
        }
      }
    });

    // If no exact hash match but we're on a page, highlight the first link in the matching group
    if (!anyMatch) {
      groups.forEach(function (group) {
        var heading = group.querySelector('.api-sidebar__heading');
        if (heading && !heading.classList.contains('collapsed')) {
          var firstLink = group.querySelector('.api-sidebar__link');
          if (firstLink) {
            var href = firstLink.getAttribute('href') || '';
            var hrefPage = href.split('#')[0] || currentPage;
            if (hrefPage === currentPage || hrefPage === '') {
              firstLink.classList.add('api-sidebar__link--active');
              anyMatch = true;
            }
          }
        }
      });
    }
  }

  /* --- Scroll Spy: highlight sidebar link as user scrolls --- */
  function initScrollSpy() {
    var sidebar = document.querySelector('.api-sidebar');
    if (!sidebar) return;

    var sections = document.querySelectorAll('section[id], .endpoint[id]');
    if (sections.length === 0) return;

    var links = sidebar.querySelectorAll('.api-sidebar__link');
    // Build a map of anchor IDs to their link elements (only same-page links)
    var currentPage = window.location.pathname.split('/').pop() || 'index.html';
    var anchorMap = {};

    links.forEach(function (link) {
      var href = link.getAttribute('href') || '';
      var hrefPage = currentPage;
      var hrefHash = href;
      if (href.indexOf('#') !== -1) {
        var parts = href.split('#');
        hrefPage = parts[0] || currentPage;
        hrefHash = '#' + parts[1];
      }
      var isCurrentPage = (hrefPage === currentPage || hrefPage === '');
      if (isCurrentPage && hrefHash.indexOf('#') === 0) {
        anchorMap[hrefHash.substring(1)] = link;
      }
    });

    if (Object.keys(anchorMap).length === 0) return;

    function updateActiveLink() {
      var scrollY = window.scrollY || window.pageYOffset;
      var viewportHeight = window.innerHeight;
      var scrollPos = scrollY + viewportHeight * 0.25; // 25% from top

      var currentId = null;
      sections.forEach(function (section) {
        if (section.offsetTop <= scrollPos) {
          currentId = section.getAttribute('id');
        }
      });

      // Clear all active states
      Object.values(anchorMap).forEach(function (link) {
        link.classList.remove('api-sidebar__link--active');
      });

      // Set active on current section's link
      if (currentId && anchorMap[currentId]) {
        anchorMap[currentId].classList.add('api-sidebar__link--active');
        // Update URL hash without triggering scroll
        if (window.history && window.history.replaceState) {
          window.history.replaceState(null, '', '#' + currentId);
        }
      }
    }

    var ticking = false;
    window.addEventListener('scroll', function () {
      if (!ticking) {
        window.requestAnimationFrame(function () {
          updateActiveLink();
          ticking = false;
        });
        ticking = true;
      }
    });

    // Run once on load (delayed to let layout settle)
    setTimeout(updateActiveLink, 100);
  }

  /* --- Endpoint Toggle (Feature detail page) --- */
  function initEndpointToggles() {
    document.querySelectorAll('.endpoint__header').forEach(function (header) {
      header.addEventListener('click', function () {
        var endpoint = header.closest('.endpoint');
        if (endpoint) endpoint.classList.toggle('open');
      });
    });
  }

  /* --- Active Page Highlighting in Nav --- */
  function initActiveNav() {
    var path = window.location.pathname.split('/').pop() || 'index.html';
    document.querySelectorAll('.nav__link').forEach(function (link) {
      var href = link.getAttribute('href');
      if (href === path || (path === '' && href === 'index.html')) {
        link.classList.add('nav__link--active');
      }
    });
  }

  /* --- JSON Syntax Highlighting (lightweight) --- */
  function highlightJson(codeEl) {
    var text = codeEl.textContent;

    var highlighted = text.replace(
      /("(?:\\.|[^"\\])*")\s*:/g,
      '<span class="json-key">$1</span>:'
    ).replace(
      /:\s*("(?:\\.|[^"\\])*")/g,
      ': <span class="json-string">$1</span>'
    ).replace(
      /:\s*(\d+\.?\d*)/g,
      ': <span class="json-number">$1</span>'
    ).replace(
      /:\s*(true|false)/g,
      ': <span class="json-boolean">$1</span>'
    ).replace(
      /:\s*(null)/g,
      ': <span class="json-null">$1</span>'
    ).replace(
      // String values in arrays
      /(\[\s*)("(?:\\.|[^"\\])*")/g,
      '$1<span class="json-string">$2</span>'
    ).replace(
      // Standalone strings in arrays (after comma)
      /,\s*("(?:\\.|[^"\\])*")/g,
      ', <span class="json-string">$1</span>'
    );

    codeEl.innerHTML = highlighted;
  }

  function initJsonHighlighting() {
    document.querySelectorAll('.code-block code.language-json').forEach(highlightJson);
  }

  /* --- Init All --- */
  document.addEventListener('DOMContentLoaded', function () {
    initHamburger();
    initCopyButtons();
    initScrollReveal();
    initSidebarToggle();
    initSidebarGroups();
    initActiveSidebar();
    initScrollSpy();
    initEndpointToggles();
    initActiveNav();
    initJsonHighlighting();
  });
})();
