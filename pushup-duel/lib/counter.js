// Счётчик отжиманий по точкам скелета MediaPipe Pose.
// Модуль без DOM: принимает 33 точки кадра и время, возвращает глубину и событие «повтор».
//
// Идея: в упоре лёжа кисти стоят на полу, а плечи ходят вверх-вниз.
// Сигнал — расстояние по вертикали от линии плеч до линии кистей.
// Вверху оно максимальное (руки прямые), внизу почти нулевое.
// Верхнюю точку подстраиваем на лету, поэтому калибровка не нужна
// и неважно, на каком расстоянии от камеры стоит телефон.

export const LM = {
  NOSE: 0, L_EYE: 2, R_EYE: 5,
  L_SHOULDER: 11, R_SHOULDER: 12,
  L_ELBOW: 13, R_ELBOW: 14,
  L_WRIST: 15, R_WRIST: 16,
};

// Пороги глубины для разной строгости засчёта.
export const STRICTNESS = {
  easy:   { down: 0.30, up: 0.16 },
  normal: { down: 0.40, up: 0.20 },
  strict: { down: 0.52, up: 0.22 },
};

const vis = (p) => (p && (p.visibility ?? 1)) || 0;

export class RepCounter {
  constructor(opts = {}) {
    this.setStrictness(opts.strictness || 'normal');
    this.minRepMs = opts.minRepMs ?? 380;   // быстрее этого не бывает: защита от дрожи
    this.windowMs = opts.windowMs ?? 2500;  // за сколько последних мс ищем «верх»
    this.reset();
  }

  setStrictness(name) {
    const t = STRICTNESS[name] || STRICTNESS.normal;
    this.downAt = t.down;
    this.upAt = t.up;
  }

  reset() {
    this.count = 0;
    this.phase = 'up';      // 'up' — ждём опускания, 'down' — ждём подъёма
    this.top = 0;           // опорная «верхняя» дистанция плечи—кисти
    this.hist = [];         // [t, s] за последние windowMs — из них берём максимум
    this.smooth = null;     // сглаженная дистанция
    this.depth = 0;         // 0 — руки прямые, 1 — грудь у пола
    this.maxDepthInRep = 0;
    this.lastRepAt = -1e9;
    this.lastSeenAt = -1e9;
    this.lastT = null;
    this.visible = false;
  }

  // landmarks: массив из 33 точек {x, y, visibility} в долях кадра; t — мс.
  // Возвращает { visible, depth, rep, count, repDepth }.
  update(landmarks, t) {
    const res = { visible: false, depth: this.depth, rep: false, count: this.count, repDepth: 0 };
    const dt = this.lastT == null ? 33 : Math.min(250, Math.max(1, t - this.lastT));
    this.lastT = t;
    if (!landmarks || landmarks.length < 17) return this._lost(res, t);

    const ls = landmarks[LM.L_SHOULDER], rs = landmarks[LM.R_SHOULDER];
    const lw = landmarks[LM.L_WRIST], rw = landmarks[LM.R_WRIST];
    // Нужна хотя бы одна пара плечо+кисть с одной стороны.
    const left = vis(ls) > 0.5 && vis(lw) > 0.5;
    const right = vis(rs) > 0.5 && vis(rw) > 0.5;
    if (!left && !right) return this._lost(res, t);

    let shoulderY, wristY;
    if (left && right) {
      shoulderY = (ls.y + rs.y) / 2;
      wristY = (lw.y + rw.y) / 2;
    } else if (left) {
      shoulderY = ls.y; wristY = lw.y;
    } else {
      shoulderY = rs.y; wristY = rw.y;
    }
    const d = Math.max(0, wristY - shoulderY);

    // Сглаживание: кадры дрожат, особенно на слабом свете.
    const a = 1 - Math.exp(-dt / 55);
    this.smooth = this.smooth == null ? d : this.smooth + a * (d - this.smooth);
    const s = this.smooth;

    // Верхняя опора — максимум дистанции за последние ~2,5 с. Так она быстро забывает,
    // что человек стоял на коленях или отодвинул телефон. Пока идёт повтор (фаза 'down'),
    // опора только растёт: долгая пауза внизу не превратится в ложный повтор.
    this.hist.push([t, s]);
    while (this.hist.length && t - this.hist[0][0] > this.windowMs) this.hist.shift();
    if (this.phase === 'down') this.top = Math.max(this.top, s);
    else {
      let m = 0;
      for (const [, v] of this.hist) if (v > m) m = v;
      this.top = m;
    }
    if (this.top < 0.05) { // слишком мелко — человек не в кадре или стоит
      this.depth = 0;
      this.visible = true;
      this.lastSeenAt = t;
      return { ...res, visible: true, depth: 0 };
    }

    this.depth = Math.min(1, Math.max(0, 1 - s / this.top));
    this.visible = true;
    this.lastSeenAt = t;
    res.visible = true;
    res.depth = this.depth;

    if (this.phase === 'up') {
      if (this.depth >= this.downAt) {
        this.phase = 'down';
        this.maxDepthInRep = this.depth;
      }
    } else {
      this.maxDepthInRep = Math.max(this.maxDepthInRep, this.depth);
      // Подъём засчитываем, когда глубина вернулась к порогу «вверх» или — при быстрых
      // повторах, когда руки не успевают выпрямиться до конца, — когда человек поднялся
      // от самой нижней точки на заметную долю амплитуды.
      const upLine = Math.max(this.upAt, Math.min(this.upAt + 0.15, this.maxDepthInRep - 0.3));
      if (this.depth <= upLine) {
        this.phase = 'up';
        if (t - this.lastRepAt >= this.minRepMs) {
          this.count += 1;
          this.lastRepAt = t;
          res.rep = true;
          res.repDepth = this.maxDepthInRep;
        }
        this.maxDepthInRep = 0;
      }
    }
    res.count = this.count;
    return res;
  }

  _lost(res, t) {
    // Короткие пропуски не сбрасывают фазу; через 1,5 с без человека — сброс опоры.
    if (t - this.lastSeenAt > 1500) {
      this.top = 0;
      this.hist = [];
      this.smooth = null;
      this.phase = 'up';
      this.depth = 0;
    }
    this.visible = false;
    res.count = this.count;
    res.depth = this.depth;
    return res;
  }
}
