// Screen-space helpers. Screenshots are 720 px wide; the phone screen is 444 px wide.
var K = 444 / 720;
function lift(el, phoneLeft, phoneTop, img, x, y, w, h) {
  el.style.left = (phoneLeft + 13 + x * K) + "px";
  el.style.top = (phoneTop + 13 + y * K) + "px";
  el.style.width = (w * K) + "px";
  el.style.height = (h * K) + "px";
  el.style.backgroundImage = "url(" + img + ")";
  el.style.backgroundSize = (720 * K) + "px auto";
  el.style.backgroundPosition = (-x * K) + "px " + (-y * K) + "px";
}
function $(s) { return document.querySelector(s); }
// The slot a lifted card leaves behind on the screen: dark, with a faint teal rim.
function hole(el, phoneLeft, phoneTop, x, y, w, h) {
  el.style.left = (phoneLeft + 13 + x * K) + "px";
  el.style.top = (phoneTop + 13 + y * K) + "px";
  el.style.width = (w * K) + "px";
  el.style.height = (h * K) + "px";
}
