// Загрузка MediaPipe Pose Landmarker. Сначала GPU, при ошибке — CPU.
// WASM и модель лежат у нас же в /mediapipe (их кладёт scripts/copy-mediapipe.mjs при сборке),
// если модели нет — берём её с серверов Google.

const LOCAL_WASM = '/mediapipe/wasm';
const LOCAL_MODEL = '/mediapipe/pose_landmarker_lite.task';
const REMOTE_MODEL = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';

let loading = null;

export function loadPose() {
  if (!loading) loading = create().catch((e) => { loading = null; throw e; });
  return loading;
}

async function modelUrl() {
  try {
    const r = await fetch(LOCAL_MODEL, { method: 'HEAD' });
    if (r.ok) return LOCAL_MODEL;
  } catch { /* нет локальной копии */ }
  return REMOTE_MODEL;
}

async function create() {
  const [{ FilesetResolver, PoseLandmarker }, model] = await Promise.all([
    import('@mediapipe/tasks-vision'),
    modelUrl(),
  ]);
  const fileset = await FilesetResolver.forVisionTasks(LOCAL_WASM);
  const opts = (delegate) => ({
    baseOptions: { modelAssetPath: model, delegate },
    runningMode: 'VIDEO',
    numPoses: 1,
    minPoseDetectionConfidence: 0.5,
    minPosePresenceConfidence: 0.5,
    minTrackingConfidence: 0.5,
  });
  const forceCpu = new URLSearchParams(location.search).get('cpu') === '1';
  if (!forceCpu) {
    try {
      return await PoseLandmarker.createFromOptions(fileset, opts('GPU'));
    } catch (e) {
      console.warn('GPU недоступен, переключаюсь на CPU', e);
    }
  }
  return PoseLandmarker.createFromOptions(fileset, opts('CPU'));
}
