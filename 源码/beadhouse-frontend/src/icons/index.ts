import SvgIcon from '@/components/SvgIcon/index.vue'
import { App } from 'vue'

const svgModules = import.meta.glob<string>('./svg/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true
})

export default (app: App) => {
  if (!document.getElementById('silvercare-svg-sprite')) {
    const namespace = 'http://www.w3.org/2000/svg'
    const sprite = document.createElementNS(namespace, 'svg')
    sprite.setAttribute('id', 'silvercare-svg-sprite')
    sprite.setAttribute('aria-hidden', 'true')
    sprite.setAttribute('style', 'position:absolute;width:0;height:0;overflow:hidden')
    Object.entries(svgModules).forEach(([filename, content]) => {
      const svg = new DOMParser().parseFromString(content, 'image/svg+xml').documentElement
      const symbol = document.createElementNS(namespace, 'symbol')
      Array.from(svg.attributes).forEach(attribute => {
        if (!['id', 'width', 'height', 'xmlns', 'xmlns:xlink'].includes(attribute.name)) {
          symbol.setAttribute(attribute.name, attribute.value)
        }
      })
      symbol.setAttribute('id', `icon-${filename.slice(filename.lastIndexOf('/') + 1, -4)}`)
      if (!svg.getAttribute('viewBox') && svg.getAttribute('width') && svg.getAttribute('height')) {
        symbol.setAttribute('viewBox', `0 0 ${svg.getAttribute('width')} ${svg.getAttribute('height')}`)
      }
      Array.from(svg.childNodes).forEach(child => symbol.appendChild(document.importNode(child, true)))
      sprite.appendChild(symbol)
    })
    document.body.appendChild(sprite)
  }
  app.component('svg-icon', SvgIcon)
}
