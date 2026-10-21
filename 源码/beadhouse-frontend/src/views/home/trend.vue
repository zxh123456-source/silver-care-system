<template>
  <myCard title="业务趋势" class="w-full">
    <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" />
    <div
      v-show="!loadError"
      id="myEcharts"
      style="width: 1000px; height: 333px"
      ref="Echarts"
      class="w-full"
    ></div>
  </myCard>
</template>

<script lang="ts" setup>
import type { ECharts } from '@/utils/charts'
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getBusinessTrend } from '@/apis/home'

const trendData = ref<any[]>([])
const Echarts = ref<HTMLElement | null>(null)
const loadError = ref('')
let chart: ECharts | undefined
let alive = true
const resizeChart = () => chart?.resize()

onMounted(async () => {
  window.addEventListener('resize', resizeChart)
  try {
    const response: any = await getBusinessTrend()
    if (!alive || !Echarts.value) return
    if (response.code !== 200 || !Array.isArray(response.data)) throw new Error('业务趋势加载失败，请刷新重试')
    trendData.value = response.data
    const echarts = await import('@/utils/charts')
    if (!alive || !Echarts.value) return
    chart = echarts.init(Echarts.value, 'purple-passion')
    chart.setOption({
      xAxis: { type: 'category', data: trendData.value.map(item => item.month) },
      tooltip: { trigger: 'axis' },
      yAxis: { type: 'value' },
      series: [{ data: trendData.value.map(item => item.consultNum), type: 'line', smooth: true,
        label: { show: true, position: 'top', formatter: '{c}' } }]
    })
  } catch {
    if (alive) loadError.value = '业务趋势加载失败，请刷新重试'
  }
})
onBeforeUnmount(() => {
  alive = false
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = undefined
})
</script>

<style lang="scss" scoped>
#myEcharts {
  width: 100% !important;
}
</style>
